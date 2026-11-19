"""Reconcile a failover evidence bundle offline, including each exported settlement history."""

import argparse
from collections import defaultdict
import json
from pathlib import Path
import re
import subprocess
import sys

from launcher import resolve_launcher
from verify_transport import check_probes, check_topology, number, require, uuid


MUTATIONS = {"submit", "cancel", "settle"}
BASELINE = ("baseline_submit", "app_ambiguous_cancel", "settle_input", "baseline_settle", "suffix_input", "prefix_unknown")
STAGES = ("baseline_start", "baseline_end", "cut_start", "cut_end", "before_fence_start", "before_fence_end",
          "fault", "fenced", "promotion_start", "promotion_end", "route_switch", "before_retry_start", "before_retry_end",
          "retry_start", "after_retry_end")


def _decode(text):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, f"duplicate JSON key {key}")
            result[key] = value
        return result

    def nonfinite(value):
        raise ValueError(f"non-finite JSON number {value}")

    return json.loads(text, object_pairs_hook=unique, parse_constant=nonfinite)


def load(path):
    return _decode(path.read_text(encoding="utf-8"))


def rows(path):
    return [_decode(line) for line in path.read_text(encoding="utf-8").splitlines()]


def same(left, right):
    # Python's normal equality conflates true, 1, and 1.0 in nested JSON.
    return json.dumps(left, sort_keys=True) == json.dumps(right, sort_keys=True)


def canonical(request):
    kind = request["op"]
    if kind == "submit":
        amount = number(request["amount"])
        require(amount > 0, "invalid submission amount")
        return f"payment={uuid(request['payment'])};from={uuid(request['from'])};to={uuid(request['to'])};amount={amount}"
    if kind == "cancel":
        return f"payment={uuid(request['payment'])}"
    members = sorted(uuid(value) for value in request["members"])
    require(kind == "settle" and members and len(set(members)) == len(members), "invalid settlement")
    return f"batch={uuid(request['batch'])};snapshot={number(request['revision'])};policy=multilateral-v1;members={','.join(members)}"


def parse_ledger(records):
    attempts, marks, active, workers = {}, {}, {}, {}
    previous = 0
    for seq, row in enumerate(records, 1):
        stamp = number(row["t_ns"])
        require(number(row["seq"]) == seq and stamp >= previous, "external ledger sequence or time regression")
        previous = stamp
        event = row["event"]
        if event == "mark":
            label = row["label"]
            marks.setdefault(label, []).append(row)
            continue
        worker = row["worker"]
        if event == "worker_ready":
            require(worker not in workers and number(row["pid"]) > 0 and number(row["launcher_pid"]) > 0,
                    "invalid worker startup")
            require(all(w["ready"]["pid"] != row["pid"] for w in workers.values() if "exit" not in w),
                    "overlapping workers share a JVM PID")
            workers[worker] = {"ready": row}
            continue
        require(worker in workers and "exit" not in workers[worker], "unknown or exited worker")
        if event == "worker_exit":
            ready = workers[worker]["ready"]
            require(worker not in active and row["pid"] == ready["pid"] and row["launcher_pid"] == ready["launcher_pid"],
                    "worker exit does not match completed process")
            workers[worker]["exit"] = row
            continue
        aid = uuid(row["attempt"])
        if event == "send":
            require(aid not in attempts and worker not in active, "duplicate or overlapping worker attempt")
            attempts[aid] = {"send": row, "checkpoints": [], "terminal": None}
            active[worker] = aid
            continue
        require(aid in attempts and active.get(worker) == aid, "reply for inactive/foreign attempt")
        attempt = attempts[aid]
        request = attempt["send"]["request"]
        if event == "checkpoint":
            frame = row["response"]
            require(frame["event"] == request["checkpoint"] and frame["command"] == request["command"]
                    and not attempt["checkpoints"], "duplicate or unrelated commit checkpoint")
            if frame["event"] == "afterCommit":
                require(number(frame["pid"]) == workers[worker]["ready"]["pid"], "checkpoint belongs to a different JVM")
            attempt["checkpoints"].append(row)
            continue
        if event == "error" and request["op"] in MUTATIONS:
            require(row["response"]["event"] == "error" and "error" not in attempt, "invalid worker error")
            attempt["error"] = row
            continue
        expected_result = {"migrate": "ok", "init": "ok", "receipt": "receipt", "snapshot": "snapshot"}
        if event == "ack":
            frame = row["response"]
            receipt = frame["receipt"]
            number(receipt["revision"])
            require(request["op"] in MUTATIONS and frame["event"] == "ack" and "error" not in attempt
                    and frame["command"] == receipt["commandId"] == request["command"]
                    and receipt["kind"] == request["op"].upper() and receipt["payload"] == canonical(request),
                    "ACK disagrees with submitted command")
        elif event == "result":
            require(row["response"]["event"] == expected_result.get(request["op"]), "mismatched operation result")
        else:
            require(event == "unknown" and bool(row["reason"]), "unexpected terminal ledger record")
        attempt["terminal"] = row
        del active[worker]
    require(not active, "unresolved ledger attempts without terminal records")
    require(all("exit" in worker for worker in workers.values()), "missing worker exit")
    return attempts, marks, workers


def _index(history):
    receipts = {uuid(r["commandId"]): r for r in history["receipts"]}
    require(len(receipts) == len(history["receipts"]), "duplicate historical receipt")
    return receipts


def _check_sync_wait(trial, attempt):
    wait = trial["sync_wait"]
    before = attempt["checkpoints"]
    require(len(before) == 1 and before[0]["response"]["event"] == "beforeCommit", "missing pre-COMMIT checkpoint")
    frame = before[0]["response"]
    pid = number(frame["backendPid"])
    require(pid > 0 and number(wait["backend_pid"]) == pid
            and wait["attempt_id"] == attempt["send"]["attempt"]
            and frame["command"] == wait["command"] == trial["roles"]["suffix_settle"]
            and frame["synchronousCommit"] == wait["synchronous_commit"] == "on", "wrong sync client session")
    samples = wait["samples"]
    times = [before[0]["t_ns"]]
    for sample in samples:
        require(number(sample["backend_pid"]) == pid and sample["wait_event_type"] == "IPC"
                and sample["wait_event"] == "SyncRep", "backend was not waiting for synchronous replication")
        times.extend([number(sample["start_ns"]), number(sample["end_ns"])])
    times.append(number(wait["no_ack_until_ns"]))
    require(len(samples) >= 2 and times == sorted(times) and times[-1] <= trial["timeline"]["fault_ns"],
            "invalid sync wait observation interval")
    lower_bound = samples[-1]["start_ns"] - samples[0]["end_ns"]
    require(lower_bound >= number(wait["minimum_wait_ns"]) >= 1_000_000_000
            and attempt["terminal"]["event"] == "unknown" and attempt["terminal"]["t_ns"] >= times[-1],
            "insufficient evidence of a blocked commit without ACK")
    return lower_bound


def reconcile(trial, histories, attempts, workers):
    mode, roles, timeline = trial["mode"], trial["roles"], trial["timeline"]
    suffix = ["suffix_settle", "suffix_unknown"] if mode == "async" else ["suffix_settle"]
    require(set(roles) == set(BASELINE) | set(suffix)
            and len(set(map(uuid, roles.values()))) == len(roles), "missing, duplicate or unexpected scenario commands")
    domain = uuid(trial["domain"])
    for name, history in histories.items():
        if name == "service-after":
            continue
        require(history["domainId"] == domain, "history belongs to another domain")
    baseline, old, survivor, final = [histories[name] for name in ("baseline", "before-fence", "before-retry", "after-retry")]
    require(same(survivor, baseline), "promoted state before retry differs from the replicated baseline")
    baseline_ids = {roles[name] for name in BASELINE}
    all_ids = set(roles.values())
    base_receipts, old_receipts, pre, post = map(_index, [baseline, old, survivor, final])
    require(set(base_receipts) == baseline_ids and set(post) == all_ids, "unexpected baseline or recovered receipt identities")
    require(all(same(old_receipts.get(k), v) and same(v, post[k]) for k, v in base_receipts.items()),
            "a prefix receipt changed across promotion or retry")
    if mode == "async":
        require(trial["sync_wait"] is None and same(old, final), "async recovery did not recreate pre-fence state")
    else:
        require(set(old_receipts) in (baseline_ids, all_ids), "unexpected original sync-primary state")
        require(same(old, baseline if set(old_receipts) == baseline_ids else final),
                "inconsistent original sync-primary facts")

    groups = defaultdict(list)
    for item in attempts.values():
        req = item["send"]["request"]
        if req["op"] in MUTATIONS and req["domain"] == domain:
            require(req["command"] in all_ids, "unrecognized external mutation")
            groups[req["command"]].append(item)
    require(set(groups) == all_ids, "history includes commands absent from client ledger")
    acked, unknown, unknown_to_ack = set(), set(), {}
    prior_attempts = {}
    for role, command in roles.items():
        group = groups[command]
        requests = [item["send"]["request"] for item in group]
        request = {k: v for k, v in requests[0].items() if k != "checkpoint"}
        require(all(same({k: v for k, v in r.items() if k != "checkpoint"}, request) for r in requests),
                "retry changed the original request")
        require(post[command]["payload"] == canonical(request)
                and post[command]["kind"] == request["op"].upper(), "request and recovered history disagree")
        before = [item for item in group if item["send"]["t_ns"] < timeline["before_retry_end_ns"]]
        after = [item for item in group if item["send"]["t_ns"] >= timeline["retry_start_ns"]]
        require(len(before) == (2 if role == "app_ambiguous_cancel" else 1) and len(after) == 1
                and len(group) == len(before) + len(after), "missing trial attempt or premature replay")
        first = prior_attempts[role] = before[0]
        source, destination = (trial["topology"]["initial"][n]["endpoint"] for n in ("primary", "standby"))
        require(all(workers[a["send"]["worker"]]["ready"]["endpoint"] == source for a in before)
                and workers[after[0]["send"]["worker"]]["ready"]["endpoint"] == destination,
                "business commands were routed to an unexpected node")
        if role in BASELINE:
            require(before[-1]["terminal"]["t_ns"] <= timeline["baseline_start_ns"], "baseline command outside baseline")
        else:
            require(timeline["cut_end_ns"] <= first["send"]["t_ns"] <= timeline["before_fence_start_ns"],
                    "suffix is outside controlled WAL cutoff")
        for item in before:
            terminal = item["terminal"]
            if terminal["event"] == "ack":
                require(terminal["t_ns"] < timeline["fault_ns"]
                        and same(terminal["response"]["receipt"], old_receipts[command]), "pre-failover ACK differs from old history")
                acked.add(command)
            else:
                require(terminal["event"] == "unknown" and terminal["t_ns"] <= timeline["before_retry_end_ns"],
                        "incorrect pre-retry result boundary")
        terminal = after[0]["terminal"]
        require(terminal["event"] == "ack" and same(terminal["response"]["receipt"], post[command])
                and terminal["t_ns"] <= timeline["after_retry_end_ns"], "retry ACK differs from final history")
        if first["terminal"]["event"] == "unknown":
            resolution = before[1] if len(before) > 1 else after[0]
            unknown_to_ack[command] = resolution["terminal"]["t_ns"] - first["terminal"]["t_ns"]
        if command not in acked:
            unknown.add(command)

    for role in ["app_ambiguous_cancel", "prefix_unknown"] + (["suffix_unknown"] if mode == "async" else []):
        attempt = prior_attempts[role]
        require(len(attempt["checkpoints"]) == 1 and attempt["checkpoints"][0]["response"]["event"] == "afterCommit"
                and attempt["terminal"]["event"] == "unknown", "withheld ACK mistaken for a real receipt")
    cancelled = groups[roles["app_ambiguous_cancel"]]
    first, retry = cancelled[:2]
    require(first["send"]["worker"] != retry["send"]["worker"] and retry["terminal"]["event"] == "ack"
            and first["terminal"]["t_ns"] <= retry["send"]["t_ns"], "app crash did not recover through another instance")
    app_a, app_b = (workers[item["send"]["worker"]] for item in (first, retry))
    require(app_b["ready"]["t_ns"] <= first["send"]["t_ns"] and app_a["exit"]["forced"] is True
            and app_a["exit"]["launcher_exit_code"] != 0 and app_a["exit"]["t_ns"] <= retry["send"]["t_ns"]
            and {first["send"]["worker"]: app_a["ready"]["pid"], retry["send"]["worker"]: app_b["ready"]["pid"]}
            == trial["original_workers"], "app crash is not substantiated by independent process lifetimes")
    lookups = [item for item in attempts.values() if item["send"]["request"] ==
               {"op": "receipt", "domain": domain, "command": roles["app_ambiguous_cancel"]}
               and item["send"]["worker"] == retry["send"]["worker"] and
               first["terminal"]["t_ns"] <= item["send"]["t_ns"] <= item["terminal"]["t_ns"] <= retry["send"]["t_ns"]]
    require(any(same(item["terminal"].get("response", {}).get("receipt"), pre[roles["app_ambiguous_cancel"]])
                for item in lookups), "missing independent receipt read before app-level retry")
    expected_unknown = {roles["prefix_unknown"], roles["suffix_unknown" if mode == "async" else "suffix_settle"]}
    require(unknown == expected_unknown, "pre-failover ACK/unknown partition disagrees with experiment")
    resolved = {roles["app_ambiguous_cancel"]: min(item["terminal"]["t_ns"] for item in lookups)
                - first["terminal"]["t_ns"]}
    # An absent receipt becomes definitive only when it is read from the fenced
    # survivor. This also resolves whether the retained prefix survived.
    snapshot_time = next(item["terminal"]["t_ns"] for item in attempts.values()
                         if item["send"]["request"] == {"op": "snapshot", "domain": domain}
                         and timeline["before_retry_start_ns"] <= item["send"]["t_ns"]
                         <= item["terminal"]["t_ns"] <= timeline["before_retry_end_ns"])
    for command in unknown:
        resolved[command] = snapshot_time - groups[command][0]["terminal"]["t_ns"]
    require(all(duration >= 0 for duration in resolved.values()), "unknown outcome was recorded after its resolution")
    lost = acked - set(pre)
    require(lost == ({roles["suffix_settle"]} if mode == "async" else set()), "unexpected acknowledged command loss")
    wait_ns = _check_sync_wait(trial, prior_attempts["suffix_settle"]) if mode == "sync" else None
    baseline_checkpoint = prior_attempts["baseline_submit"]["checkpoints"]
    require(len(baseline_checkpoint) == 1 and baseline_checkpoint[0]["response"]["synchronousCommit"] == "on",
            "missing baseline transaction durability observation")
    require(post[roles["baseline_settle"]]["outcome"] == post[roles["suffix_settle"]]["outcome"] == "COMMITTED",
            "settlement did not commit")
    account_a = prior_attempts["baseline_submit"]["send"]["request"]["from"]
    account_b = prior_attempts["baseline_submit"]["send"]["request"]["to"]
    require(same(trial["openings"], {account_a: 100, account_b: 0}), "incorrect initial case funding")
    for history, revision, a, b in [(baseline, 6, "75", "25"), (final, len(roles), "82", "18")]:
        require(number(history["revision"]) == revision and
                {account["id"]: account["balance"] for account in history["accounts"]} == {account_a: a, account_b: b},
                "case balances or revision disagree with the funded fixture")
    wait_client_ns = (trial["sync_wait"]["no_ack_until_ns"] - prior_attempts["suffix_settle"]["send"]["t_ns"]
                      if mode == "sync" else None)
    return {"acked_before_fault": sorted(acked), "acknowledged_missing": sorted(lost),
            "acknowledged_survived": sorted(acked & set(pre)), "unknown_before_retries": sorted(unknown),
            "unknown_survived": sorted(unknown & set(pre)), "unknown_missing": sorted(unknown - set(pre)),
            "unknown_to_retry_ack_ns": unknown_to_ack, "sync_wait_lower_bound_ns": wait_ns,
            "unknown_to_resolution_ns": resolved, "sync_client_unacknowledged_ns": wait_client_ns}


def check_snapshots(trial, histories, attempts, workers):
    timeline = trial["timeline"]
    initial = trial["topology"]["initial"]
    # Bind every exported file to a read-only history result recorded independently
    # by the client, from the expected physical node in the observation window.
    def observed(history_name, node, start, end):
        expected = histories[history_name]
        for item in attempts.values():
            sent, reply = item["send"], item["terminal"]
            if (sent["request"] == {"op": "snapshot", "domain": expected["domainId"]}
                    and timeline[start] <= sent["t_ns"] <= reply["t_ns"] <= timeline[end]
                    and workers[sent["worker"]]["ready"]["endpoint"] == initial[node]["endpoint"]
                    and same(reply.get("response", {}).get("history"), expected)):
                return
        raise ValueError(f"missing independent {node} snapshot for {history_name}")

    observed("baseline", "primary", "baseline_start_ns", "baseline_end_ns")
    observed("baseline", "standby", "baseline_end_ns", "cut_start_ns")
    observed("before-fence", "primary", "before_fence_start_ns", "before_fence_end_ns")
    observed("before-retry", "standby", "before_retry_start_ns", "before_retry_end_ns")
    observed("after-retry", "standby", "retry_start_ns", "after_retry_end_ns")
    observed("service-after", "standby", "route_switch_ns", "before_retry_start_ns")


def check_service(trial, history, attempts, workers):
    service, timeline = trial["service_probe"], trial["timeline"]
    domain = uuid(service["domain"])
    require(domain != trial["domain"] and history["domainId"] == domain, "service writes overlap the measured domain")
    receipts = _index(history)
    initial = trial["topology"]["initial"]
    commands = {}
    for phase, op, node in [("before", "submit", "primary"), ("after", "settle", "standby")]:
        measurement = service[phase]
        command = uuid(measurement["command"])
        require(command not in trial["roles"].values(), "service probe reused a measured command")
        candidates = [item for item in attempts.values() if item["send"]["request"].get("domain") == domain
                      and item["send"]["request"]["op"] == op and item["send"]["request"]["command"] == command]
        require(len(candidates) == 1, "service probe lacks a single fresh command")
        sent, ack = candidates[0]["send"], candidates[0]["terminal"]
        receipt = receipts[command]
        require(ack["event"] == "ack" and ack["t_ns"] == number(measurement["ack_ns"])
                and same(ack["response"]["receipt"], receipt)
                and workers[sent["worker"]]["ready"]["endpoint"] == initial[node]["endpoint"],
                "service ACK or routing mismatch")
        lookups = [item for item in attempts.values() if item["send"]["request"] == {"op":"receipt","domain":domain,"command":command}
                   and item["send"]["worker"] != sent["worker"] and item["send"]["t_ns"] >= ack["t_ns"]
                   and workers[item["send"]["worker"]]["ready"]["endpoint"] == initial[node]["endpoint"]
                   and item["terminal"]["t_ns"] == number(measurement["readback_ns"])]
        require(len(lookups) == 1 and same(lookups[0]["terminal"]["response"]["receipt"], receipt),
                "service probe lacks independent receipt readback")
        require((phase == "before" and measurement["readback_ns"] <= timeline["cut_start_ns"])
                or (phase == "after" and timeline["route_switch_ns"] <= sent["t_ns"] <= measurement["readback_ns"]
                    <= timeline["before_retry_start_ns"]), "service probe outside measured window")
        commands[phase] = sent["request"]
    require(set(receipts) == {item["command"] for item in commands.values()}
            and commands["after"]["members"] == [commands["before"]["payment"]]
            and receipts[commands["after"]["command"]]["outcome"] == "COMMITTED"
            and receipts[commands["after"]["command"]]["gross"] == "1"
            and sorted(a["balance"] for a in history["accounts"]) == ["1", "1"], "invalid fresh service settlement")
    for attempt in attempts.values():
        req = attempt["send"]["request"]
        if req["op"] in MUTATIONS and req["domain"] != trial["domain"]:
            require(req["domain"] == domain and req["command"] in receipts, "untracked financial mutation")
    return {"service_rto_ns": service["after"]["ack_ns"] - trial["topology"]["fence"]["started_ns"],
            "service_ack_gap_ns": service["after"]["ack_ns"] - service["before"]["ack_ns"],
            "service_readback_ns": service["after"]["readback_ns"] - service["after"]["ack_ns"],
            "service_cut_to_ack_ns": service["after"]["ack_ns"] - timeline["cut_start_ns"]}


def verify_trial(trial_dir, launcher=None):
    trial_dir = Path(trial_dir)
    launcher = resolve_launcher(launcher)
    try:
        trial = load(trial_dir / "trial.json")
        require(number(trial["version"]) == 1 and number(trial["repetition"]) >= 1
                and trial["mode"] in {"async", "sync"}, "invalid trial metadata")
        timeline = trial["timeline"]
        stamps = [number(timeline[f"{key}_ns"]) for key in STAGES]
        require(stamps == sorted(stamps), "invalid observation/retry chronology")
        attempts, marks, workers = parse_ledger(rows(trial_dir / "ledger.jsonl"))
        for key in STAGES:
            records = marks.get(key, marks.get(key + "_ns", []))
            require(len(records) == 1 and records[0]["t_ns"] == timeline[key + "_ns"], f"ledger marker mismatch: {key}")
        check_topology(trial["mode"], trial["topology"], timeline)
        metrics = check_probes(rows(trial_dir / "probes.jsonl"), trial)
        histories = {}
        for name in ("baseline", "before-fence", "before-retry", "after-retry", "service-after"):
            path = trial_dir / f"{name}.json"
            result = subprocess.run([str(launcher), "verify", str(path.resolve())], capture_output=True,
                                    text=True, errors="replace", timeout=30)
            require(result.returncode == 0, f"Java replay rejected {name}: {result.stderr.strip()}")
            histories[name] = load(path)
        check_snapshots(trial, histories, attempts, workers)
        return {**reconcile(trial, histories, attempts, workers),
                **check_service(trial, histories["service-after"], attempts, workers), **metrics}
    except (KeyError, IndexError, TypeError, AttributeError, ValueError) as exc:
        raise ValueError(f"{trial_dir}: {exc}") from exc


def verify_bundle(path, launcher):
    environment = load(path / "environment.json")
    for field in ("started_utc", "host", "host_architecture", "python", "java", "compose"):
        require(isinstance(environment[field], str) and environment[field].strip(), f"missing environment {field}")
    docker = environment["docker"]
    require(all(isinstance(docker[k], str) and docker[k].strip() for k in ("server_version", "os", "architecture"))
            and number(docker["cpus"]) > 0 and number(docker["memory_bytes"]) > 0, "missing Docker environment")
    for key in ("application_jars_sha256", "harness_files_sha256"):
        hashes = environment[key]
        require(isinstance(hashes, dict) and hashes
                and all(re.fullmatch(r"[0-9a-f]{64}", digest) for digest in hashes.values()),
                f"missing or invalid provenance hashes: {key}")
    require({"compose.yaml", "run.py", "verify.py", "verify_transport.py", "experiment.py", "probes.py",
             "client.py", "topology.py"} <= environment["harness_files_sha256"].keys(),
            "provenance missing a required harness component")
    manifest = load(path / "manifest.json")
    modes = manifest["modes"]
    require(number(manifest["version"]) == 1 and modes in (["async"], ["sync"], ["async", "sync"]), "invalid manifest")
    repetitions = number(manifest["repetitions"])
    require(repetitions >= 1 and len(manifest["trials"]) == repetitions * len(modes), "incomplete trial manifest")
    expected = [f"{mode}-{n:02d}" for n in range(1, repetitions + 1) for mode in modes]
    require(manifest["trials"] == expected and {d.name for d in path.iterdir() if d.is_dir()} == set(expected),
            "manifest and trial directories disagree")
    computed = {}
    projects, domains = set(), set()
    for name in expected:
        trial = load(path / name / "trial.json")
        require(name == f"{trial['mode']}-{number(trial['repetition']):02d}", "trial identity mismatch")
        project, domain = trial["topology"]["initial"]["project"], trial["domain"]
        require(project not in projects and domain not in domains, "reused physical project or domain")
        projects.add(project)
        domains.add(domain)
        computed[name] = verify_trial(path / name, launcher)
    require(same(load(path / "summary.json"), computed), "published summary differs from raw evidence")
    return computed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--launcher", type=Path)
    args = parser.parse_args()
    launcher = resolve_launcher(args.launcher)
    path = args.directory
    if (path / "trial.json").is_file():
        summary = verify_trial(path, launcher)
    else:
        summary = verify_bundle(path, launcher)
    print(json.dumps(summary, indent=2, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as exc:
        sys.exit(f"Invalid failover evidence: {exc}")
