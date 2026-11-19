"""Execute one physical failover trial. The external host owns all observation times."""

import json
import os
import time
import uuid
from contextlib import ExitStack
from pathlib import Path

from client import Ledger, Worker, WorkerError, now_ns
from probes import ProbeMonitor
from topology import Topology, lsn_bytes


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def write_new(path, value):
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, indent=2, sort_keys=True, allow_nan=False)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())


def fixture(mode):
    namespace = uuid.uuid4()
    domain = str(namespace)

    def identity(name):
        return str(uuid.uuid5(namespace, name))

    a, b = identity("account-A"), identity("account-B")
    requests = {}

    def add(role, op, **fields):
        requests[role] = dict(op=op, domain=domain, command=identity(role), **fields)

    def submit(role, payer, payee, amount):
        add(role, "submit", payment=identity(role + "-payment"),
            **{"from": payer, "to": payee, "amount": amount})

    submit("baseline_submit", a, b, 30)
    add("app_ambiguous_cancel", "cancel", payment=requests["baseline_submit"]["payment"])
    submit("settle_input", a, b, 25)
    add("baseline_settle", "settle", batch=identity("baseline-batch"), revision=3,
        members=[requests["settle_input"]["payment"]])
    submit("suffix_input", b, a, 7)
    submit("prefix_unknown", a, b, 4)
    add("suffix_settle", "settle", batch=identity("suffix-batch"), revision=6,
        members=[requests["suffix_input"]["payment"]])
    if mode == "async":
        submit("suffix_unknown", a, b, 13)
    return domain, {a: 100, b: 0}, requests


def ack(frame, request):
    outcome = {"submit": "QUEUED", "cancel": "CANCELLED", "settle": "COMMITTED"}[request["op"]]
    receipt = frame.get("receipt", {})
    require(frame["event"] == "ack" and receipt.get("commandId") == request["command"]
            and receipt.get("outcome") == outcome, f"Unexpected decision: {frame}")
    return receipt


def checkpoint(worker, request, name):
    attempt = worker.send(dict(request, checkpoint=name))
    frame = worker.read()
    require(frame["event"] == name and frame.get("command") == request["command"],
            f"Missing {name} checkpoint: {frame}")
    return attempt, frame


def snapshot(reader, domain, path):
    history = reader.request(dict(op="snapshot", domain=domain))["history"]
    require(history["domainId"] == domain, "Snapshot returned another domain")
    write_new(path, history)
    return history


def service_probe(writer, reader, request):
    receipt = ack(writer.request(request), request)
    acknowledged = writer.last_event_ns
    seen = reader.request(dict(op="receipt", domain=request["domain"], command=request["command"]))["receipt"]
    require(receipt == seen, "Independent application readback did not confirm the new receipt")
    return dict(command=request["command"], ack_ns=acknowledged, readback_ns=reader.last_event_ns)


def wait_sample(topology, backend_pid):
    # backend_pid is emitted from the exact JDBC transaction immediately before COMMIT.
    require(type(backend_pid) is int and backend_pid > 0, "Invalid checkpoint backend PID")
    deadline = now_ns() + 8_000_000_000
    while now_ns() < deadline:
        started = now_ns()
        observation = topology.sql("primary", f"""
            SELECT wait_event_type, wait_event FROM pg_stat_activity WHERE pid = {backend_pid}
            """)
        ended = now_ns()
        if observation == "IPC|SyncRep":
            return dict(start_ns=started, end_ns=ended, backend_pid=backend_pid,
                        wait_event_type="IPC", wait_event="SyncRep")
        time.sleep(0.05)
    raise RuntimeError(f"Backend {backend_pid} did not wait for synchronous replication")


def no_reply(worker, seconds):
    try:
        response = worker.read(timeout=seconds)
    except TimeoutError:
        return now_ns()
    raise RuntimeError(f"Blocked request unexpectedly completed: {response}")


def synchronous_suffix(worker, topology, request):
    attempt, frame = checkpoint(worker, request, "beforeCommit")
    require(frame.get("synchronousCommit") == "on", "Worker is not requiring remote durable flush")
    pid = frame["backendPid"]
    first = wait_sample(topology, pid)
    minimum = 1_000_000_000
    no_reply(worker, minimum / 1_000_000_000)
    second = wait_sample(topology, pid)
    require(second["start_ns"] - first["end_ns"] >= minimum, "Insufficient observed wait interval")
    return dict(attempt_id=attempt, command=request["command"], backend_pid=pid,
                synchronous_commit=frame["synchronousCommit"], minimum_wait_ns=minimum,
                samples=[first, second], no_ack_until_ns=no_reply(worker, 0))


def run_once(mode, repetition, out_dir, launcher, subnet=None):
    """Create a new trial directory, preserving partial evidence if an assertion fails."""
    domain, openings, requests = fixture(mode)
    directory = Path(out_dir)
    directory.mkdir()
    details = dict(version=1, mode=mode, repetition=repetition, domain=domain, openings=openings,
                   roles={role: body["command"] for role, body in requests.items()},
                   topology={}, timeline={}, sync_wait=None)
    facts = details["topology"]
    with Ledger(directory / "ledger.jsonl") as ledger, Topology(mode, subnet) as topology, ExitStack() as stack:
        def mark(name, **fields):
            stamp = ledger.mark(name, **fields)
            details["timeline"][name] = stamp
            return stamp

        def worker(name, url):
            return stack.enter_context(Worker(name, launcher, url, ledger))

        facts["initial"] = topology.describe()
        primary = topology.primary_url
        standby = topology.standby_url
        app_a = worker("app-A", primary)
        app_b = worker("app-B", primary)
        require(app_a.pid != app_b.pid, "Two independent application processes are required")
        details["original_workers"] = {"app-A": app_a.pid, "app-B": app_b.pid}
        app_a.request({"op": "migrate"})
        app_a.request(dict(op="init", domain=domain, openings=openings))

        # Each successful business operation is tied to a command known by the external client.
        # Readers never submit any business request.
        reader = worker("primary-history-reader", primary)
        first = requests["baseline_submit"]
        _, settings = checkpoint(app_a, first, "beforeCommit")
        require(settings.get("synchronousCommit") == "on", "Unexpected JDBC commit policy")
        ack(app_a.read(), first)
        cancel = requests["app_ambiguous_cancel"]
        checkpoint(app_a, cancel, "afterCommit")
        app_a.kill("app-crash-after-commit")
        observed = app_b.request(dict(op="receipt", domain=domain, command=cancel["command"]))["receipt"]
        require(ack(app_b.request(cancel), cancel) == observed, "Cross-application replay changed the receipt")
        for role in ("settle_input", "baseline_settle", "suffix_input"):
            request = requests[role]
            ack(app_b.request(request), request)

        # This second retained response stays unknown until the promoted timeline is inspected.
        replacement = worker("app-A-restarted", primary)
        checkpoint(replacement, requests["prefix_unknown"], "afterCommit")
        replacement.kill("reply-withheld-before-stream-cut")
        mark("baseline_start_ns")
        baseline = snapshot(reader, domain, directory / "baseline.json")
        require(baseline["revision"] == 6, "The baseline lacks its six financial mutations")
        mark("baseline_end_ns")

        probes = stack.enter_context(ProbeMonitor(topology, directory / "probes.jsonl"))
        probes.wait_verified("primary", timeout=30)
        # This fresh domain measures a real application call on each route. Its IDs
        # cannot restore a missing case receipt or change the balances under comparison.
        service_domain = str(uuid.uuid4())
        service_openings = {str(uuid.uuid4()): 2, str(uuid.uuid4()): 0}
        payer, payee = service_openings
        admission = dict(op="submit", domain=service_domain, command=str(uuid.uuid4()),
                         payment=str(uuid.uuid4()), **{"from": payer, "to": payee, "amount": 1})
        settlement = dict(op="settle", domain=service_domain, command=str(uuid.uuid4()),
                          batch=str(uuid.uuid4()), revision=1, members=[admission["payment"]])
        app_b.request(dict(op="init", domain=service_domain, openings=service_openings))
        details["service_probe"] = {
            "domain": service_domain,
            "before": service_probe(app_b, reader, admission),
        }
        facts["barrier"] = topology.barrier()
        # The promoted standby must have this exact domain, including the unacknowledged receipt.
        standby_reader = worker("standby-history-reader", standby)
        replicated = standby_reader.request(dict(op="snapshot", domain=domain))["history"]
        require(replicated == baseline, "Baseline differs on physical standby after replay barrier")
        mark("cut_start_ns")
        facts["cut"] = topology.cut()
        mark("cut_end_ns")
        suffix = requests["suffix_settle"]
        if mode == "async":
            ack(app_b.request(suffix), suffix)
            checkpoint(app_b, requests["suffix_unknown"], "afterCommit")
        else:
            details["sync_wait"] = synchronous_suffix(app_b, topology, suffix)

        mark("before_fence_start_ns")
        before_fence = snapshot(reader, domain, directory / "before-fence.json")
        mark("before_fence_end_ns")
        facts["cut_before_fence"] = topology.cut_status()
        cutoff = facts["cut"]["standby_receive_lsn"]
        last = facts["cut_before_fence"]
        require(cutoff == last["standby_receive_lsn"] and
                lsn_bytes(last["primary_flush_lsn"]) > lsn_bytes(cutoff),
                "Missing evidence of local WAL beyond a stationary receive cutoff")
        if mode == "sync":
            details["sync_wait"]["no_ack_until_ns"] = no_reply(app_b, 0)
        mark("fault_ns")
        facts["fence"] = topology.fence()
        mark("fenced_ns")
        mark("promotion_start_ns")
        facts["promotion"] = topology.promote()
        mark("promotion_end_ns")
        mark("route_switch_ns")
        probes.route("standby")
        app_new = worker("app-B-promoted", standby)
        details["service_probe"]["after"] = service_probe(app_new, standby_reader, settlement)
        service_history = snapshot(standby_reader, service_domain, directory / "service-after.json")
        require(service_history["revision"] == 2
                and [a["balance"] for a in service_history["accounts"]] == ["1", "1"]
                and service_history["payments"][0]["batchId"] == settlement["batch"],
                "Promoted service did not settle the pre-replicated payment")

        if mode == "sync":
            try:
                unexpected = app_b.read(timeout=10)
            except WorkerError:
                pass  # Commit result unknown to the external client; recorded by Worker.
            else:
                raise RuntimeError(f"Required-standby request succeeded across hard fence: {unexpected}")
        else:
            app_b.kill("reply-withheld-across-primary-fence")
        mark("before_retry_start_ns")
        survived = snapshot(standby_reader, domain, directory / "before-retry.json")
        require(survived == baseline, "Promoted history differs from the replicated prefix before any retry")
        mark("before_retry_end_ns")
        probes.wait_verified("standby", timeout=30)
        mark("retry_start_ns")
        for request in requests.values():
            ack(app_new.request(request), request)
        after = snapshot(standby_reader, domain, directory / "after-retry.json")
        mark("after_retry_end_ns")
        require(after["revision"] == len(requests), "Wrong number of unique final mutations")
        if mode == "async":
            require(after == before_fence, "Retries did not reconstruct the old primary's complete domain")
    write_new(directory / "trial.json", details)
    return details
