"""Regression mutations against evidence captured from two physical PostgreSQL 18 trials."""

import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

import verify
from verify_transport import check_probes, check_topology


FIXTURE = Path(__file__).parent / "fixtures" / "physical-trials.zip"


def case(mode):
    histories = {}
    with ZipFile(FIXTURE) as archive:
        def read(name):
            return archive.read(f"{mode}-01/{name}").decode("utf-8")

        trial = verify._decode(read("trial.json"))
        ledger = [verify._decode(line) for line in read("ledger.jsonl").splitlines()]
        probes = [verify._decode(line) for line in read("probes.jsonl").splitlines()]
        for name in ("baseline", "before-fence", "before-retry", "after-retry", "service-after"):
            histories[name] = verify._decode(read(f"{name}.json"))
    return trial, histories, ledger, probes


class EvidenceTest(unittest.TestCase):
    def test_physical_fixture_survivors_and_observation_metrics(self):
        for mode in ("async", "sync"):
            with self.subTest(mode=mode):
                trial, histories, ledger, probes = case(mode)
                attempts, _, workers = verify.parse_ledger(ledger)
                check_topology(mode, trial["topology"], trial["timeline"])
                observed = check_probes(probes, trial)
                verify.check_snapshots(trial, histories, attempts, workers)
                metrics = verify.reconcile(trial, histories, attempts, workers)
                service = verify.check_service(trial, histories["service-after"], attempts, workers)
                self.assertEqual(metrics["acknowledged_missing"], [trial["roles"]["suffix_settle"]] if mode == "async" else [])
                self.assertEqual(metrics["unknown_survived"], [trial["roles"]["prefix_unknown"]])
                self.assertEqual(observed["rto_ns"], observed["first_promoted_ack_ns"] - trial["topology"]["fence"]["started_ns"])
                self.assertEqual(service["service_rto_ns"], trial["service_probe"]["after"]["ack_ns"] - trial["topology"]["fence"]["started_ns"])

    def test_repaired_history_cannot_substitute_for_before_retry_read(self):
        trial, histories, ledger, _ = case("async")
        attempts, _, workers = verify.parse_ledger(ledger)
        histories["before-retry"] = histories["after-retry"]
        with self.assertRaisesRegex(ValueError, "missing independent standby snapshot"):
            verify.check_snapshots(trial, histories, attempts, workers)
        with self.assertRaisesRegex(ValueError, "before retry differs"):
            verify.reconcile(trial, histories, attempts, workers)

    def test_wrong_fence_even_if_two_inspections_agree(self):
        trial, _, _, _ = case("sync")
        topology = trial["topology"]
        for record in (topology["fence"], topology["promotion"]["old_primary"]):
            record["container_id"] = topology["initial"]["standby"]["container_id"]
        with self.assertRaisesRegex(ValueError, "original primary"):
            check_topology("sync", topology, trial["timeline"])

    def test_cut_requires_stationary_receive_not_only_replay(self):
        trial, _, _, _ = case("async")
        cut = trial["topology"]["cut_before_fence"]
        cut["standby_receive_lsn"] = cut["standby_replay_lsn"] = cut["primary_flush_lsn"]
        with self.assertRaisesRegex(ValueError, "stationary physical receive"):
            check_topology("async", trial["topology"], trial["timeline"])

    def test_lwlock_syncrep_cannot_substitute_for_commit_wait(self):
        trial, histories, ledger, _ = case("sync")
        attempts, _, workers = verify.parse_ledger(ledger)
        trial["sync_wait"]["samples"][1]["wait_event_type"] = "LWLock"
        with self.assertRaisesRegex(ValueError, "not waiting for synchronous replication"):
            verify.reconcile(trial, histories, attempts, workers)

    def test_rejects_misrouted_or_wrong_receipt_and_false_numeric_ack(self):
        _, _, records, _ = case("async")
        ack = next(row for row in records if row["event"] == "ack")
        ack["response"]["receipt"]["payload"] += "0"
        with self.assertRaisesRegex(ValueError, "ACK disagrees"):
            verify.parse_ledger(records)
        ack["response"]["receipt"]["revision"] = True
        with self.assertRaisesRegex(ValueError, "expected a nonnegative integer"):
            verify.parse_ledger(records)

    def test_removed_failed_probe_and_invalid_post_cut_sync_ack(self):
        trial, _, _, probes = case("sync")
        sample = next(p for p in probes if p["reason"] == "cadence" and p["status"] == "unknown"
                      and p["start_ns"] >= trial["timeline"]["cut_end_ns"])
        with self.assertRaisesRegex(ValueError, "incomplete probe cadence"):
            check_probes([p for p in probes if p is not sample], trial)
        sample.update(ack_ns=sample["end_ns"], status="unverified")
        with self.assertRaisesRegex(ValueError, "acknowledged after required standby was cut"):
            check_probes(probes, trial)

    def test_service_must_be_confirmed_by_another_worker(self):
        trial, histories, ledger, _ = case("async")
        attempts, _, workers = verify.parse_ledger(ledger)
        command = trial["service_probe"]["after"]["command"]
        related = [a["send"] for a in attempts.values() if a["send"]["request"].get("command") == command]
        settlement, lookup = related
        lookup["worker"] = settlement["worker"]
        with self.assertRaisesRegex(ValueError, "independent receipt readback"):
            verify.check_service(trial, histories["service-after"], attempts, workers)

    def test_complete_manifest_interleaves_modes_by_repetition(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            modes = ["async", "sync"]
            expected = ["async-01", "sync-01", "async-02", "sync-02"]
            def save(path, obj):
                path.write_text(json.dumps(obj), encoding="utf-8")

            env = dict.fromkeys(("started_utc", "host", "host_architecture", "python", "java", "compose"), "test")
            env["docker"] = dict(server_version="test", os="test", architecture="test", cpus=1, memory_bytes=1)
            env["application_jars_sha256"] = {"settlement-lab-test.jar": "0" * 64}
            env["harness_files_sha256"] = dict.fromkeys(("compose.yaml", "run.py", "verify.py", "verify_transport.py",
                                                         "experiment.py", "probes.py", "client.py", "topology.py"), "1"*64)
            save(root / "environment.json", env)
            save(root / "manifest.json", dict(version=1, modes=modes, repetitions=2, trials=expected))
            for name in expected:
                mode, repetition = name.split("-")
                (root / name).mkdir()
                save(root / name / "trial.json", dict(mode=mode, repetition=int(repetition), domain=name,
                                                      topology={"initial": {"project": name}}))
            save(root / "summary.json", {name: {} for name in expected})
            with patch.object(verify, "verify_trial", return_value={}):
                self.assertEqual(list(verify.verify_bundle(root, None)), expected)
                save(root / "summary.json", {name: {"rto_ns": 123} for name in expected})
                with self.assertRaisesRegex(ValueError, "summary differs"):
                    verify.verify_bundle(root, None)
