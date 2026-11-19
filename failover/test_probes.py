import json
from pathlib import Path
import re
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

import probes


class ProbeTests(unittest.TestCase):
    def test_verified_commit_needs_matching_read_on_another_psql_invocation(self):
        calls = []

        def psql(_topology, target, sql, _timeout):
            calls.append((target, sql))
            if len(calls) == 1:
                return ""
            probe_id, payload = re.findall(r"'([0-9a-f-]{36})'", calls[0][1])
            self.assertIn(probe_id, sql)
            self.assertIn("SELECT payload", sql)
            return payload

        with patch.object(probes, "_query", side_effect=psql):
            row = probes._sample(None, "primary", 0, "cadence", 2)
        self.assertEqual([target for target, _ in calls], ["primary", "primary"])
        self.assertIn("COMMIT;", calls[0][1])
        self.assertEqual(row["status"], "verified")
        self.assertEqual(row["expected"], row["observed"])
        self.assertEqual(sorted([row[k] for k in ("start_ns", "ack_ns", "observed_ns", "end_ns")]),
                         [row[k] for k in ("start_ns", "ack_ns", "observed_ns", "end_ns")])

    def test_warning_from_synchronous_commit_does_not_establish_ack(self):
        topology = Mock()
        topology.psql_command.return_value = ["docker", "exec", "test", "psql"]
        result = subprocess.CompletedProcess([], 0, "", "WARNING: canceling wait for synchronous replication")
        with patch("probes.subprocess.run", return_value=result) as run:
            row = probes._sample(topology, "primary", 0, "cadence", 2)
        run.assert_called_once()
        self.assertIsNone(row["ack_ns"])
        self.assertIsNone(row["observed_ns"])
        self.assertEqual(row["status"], "unknown")
        self.assertIn("WARNING", row["detail"])

    def test_timeout_and_readback_failure_preserve_client_observations(self):
        timed_out = probes.ProbeSQLFailure("timeout", "deadline elapsed")
        for replies, expected_status, ack in [([timed_out], "unknown", False),
                                             (["", timed_out], "unverified", True),
                                             (["", "another payload"], "unverified", True)]:
            with self.subTest(status=expected_status, ack=ack), patch.object(probes, "_query", side_effect=replies):
                row = probes._sample(None, "standby", 0, "cadence", 2)
                self.assertEqual(expected_status, row["status"])
                self.assertEqual(ack, row["ack_ns"] is not None)

    def test_probe_can_recover_while_writes_to_original_endpoint_are_pending(self):
        committed = {}
        blocked = threading.Event()
        release = threading.Event()

        def psql(_topology, target, sql, _timeout):
            if "CREATE SCHEMA" in sql:
                return ""
            ids = re.findall(r"'([0-9a-f-]{36})'", sql)
            if len(ids) == 1:
                return committed[ids[0]]
            if target == "primary" and committed:
                blocked.set()
                release.wait(3)
                raise probes.ProbeSQLFailure("timeout", "no write response")
            committed[ids[0]] = ids[1]
            return ""

        with tempfile.TemporaryDirectory() as temp, patch.object(probes, "_query", side_effect=psql):
            path = Path(temp) / "probes.jsonl"
            with probes.ProbeMonitor(None, path, interval_s=0.04) as monitor:
                monitor.wait_verified("primary", timeout=2)
                self.assertTrue(blocked.wait(2))
                monitor.route("standby")
                monitor.wait_verified("standby", timeout=2)
                release.set()
            rows = [json.loads(line) for line in path.read_text().splitlines()]
        self.assertEqual(len(rows), len({row["probe_id"] for row in rows}))
        self.assertEqual(next(r["status"] for r in rows if r["reason"] == "route-check"), "unknown")
        self.assertEqual(next(r["status"] for r in rows if r["reason"] == "route-start"), "verified")


if __name__ == "__main__":
    unittest.main()
