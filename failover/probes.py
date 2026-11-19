"""Sample acknowledged, independently visible commits while the settlement clients fail over."""

import concurrent.futures
import json
import os
from pathlib import Path
import subprocess
import threading
import time
import uuid


CREATE_TABLE = """
    CREATE SCHEMA failover_monitor;
    CREATE TABLE failover_monitor.commit_probe (probe_id uuid PRIMARY KEY, payload uuid NOT NULL);
"""


class ProbeSQLFailure(RuntimeError):
    def __init__(self, kind, detail):
        super().__init__(detail)
        self.kind = kind


def _query(topology, target, sql, timeout_s):
    # The psql client stays on the surviving container, reaching either database by TCP.
    command = topology.psql_command(target, client_node="standby")
    # Stop psql inside Docker even if the supervisor's docker exec process times out.
    index = command.index("psql")
    command[index:index] = ["timeout", "--kill-after=0.5s", f"{timeout_s}s"]
    try:
        result = subprocess.run([*command, "-c", sql], capture_output=True, text=True,
                                encoding="utf-8", errors="replace", timeout=timeout_s + 1)
    except subprocess.TimeoutExpired as exc:
        raise ProbeSQLFailure("timeout", "host deadline waiting for psql") from exc
    message = result.stderr.strip()
    if result.returncode in (124, 137):
        raise ProbeSQLFailure("timeout", "psql client deadline elapsed")
    if result.returncode or message:
        # A cancelled SyncRep wait can return a SQL WARNING and a local commit. Neither
        # that warning nor a lost socket establishes a successful synchronous ACK.
        kind = "error"
        if (message.startswith("psql: error: connection to server at")
                or message.startswith("psql: error: could not translate host name")):
            kind = "connect"
        raise ProbeSQLFailure(kind, (message or f"psql exit {result.returncode}")[:1200])
    return result.stdout.strip()


def _sample(topology, target, scheduled_ns, reason, timeout_s, started=None):
    probe_id, payload = str(uuid.uuid4()), str(uuid.uuid4())
    row = dict(probe_id=probe_id, expected=payload, observed=None, target=target,
               reason=reason, scheduled_ns=scheduled_ns, start_ns=time.perf_counter_ns(),
               ack_ns=None, observed_ns=None, error_kind=None, detail=None)
    if started is not None:
        started.set()
    try:
        _query(topology, target, f"""
            BEGIN;
            SET LOCAL synchronous_commit = on;
            SET LOCAL statement_timeout = 0;
            INSERT INTO failover_monitor.commit_probe (probe_id, payload)
                VALUES ('{probe_id}', '{payload}');
            COMMIT;
        """, timeout_s)
        row["ack_ns"] = time.perf_counter_ns()
        row["observed"] = _query(topology, target,
            f"SELECT payload FROM failover_monitor.commit_probe WHERE probe_id = '{probe_id}'", timeout_s)
        row["observed_ns"] = time.perf_counter_ns()
        if row["observed"] == payload:
            row["status"] = "verified"
        else:
            row.update(status="unverified", error_kind="readback", detail="committed payload did not match")
    except ProbeSQLFailure as exc:
        if row["ack_ns"] is not None:
            status = "unverified"
        else:
            status = "failed" if exc.kind == "connect" else "unknown"
        row.update(status=status, error_kind=exc.kind, detail=str(exc))
    row["end_ns"] = time.perf_counter_ns()
    return row


class ProbeMonitor:
    def __init__(self, topology, path, *, interval_s=0.5, timeout_s=2):
        if interval_s <= 0 or timeout_s <= 0:
            raise ValueError("Probe intervals and deadlines must be positive")
        self.topology = topology
        self.path = Path(path)
        self.interval_s = interval_s
        self.timeout_s = timeout_s
        self._condition = threading.Condition()
        self._stop = threading.Event()
        self._target = "primary"
        self._verified = {}
        self._error = None
        self._pending = 0

    def __enter__(self):
        if self.path.exists():
            raise FileExistsError(self.path)
        _query(self.topology, "primary", CREATE_TABLE, 15)
        self._output = self.path.open("x", encoding="utf-8", newline="\n")
        self._pool = concurrent.futures.ThreadPoolExecutor(max_workers=16, thread_name_prefix="commit-probe")
        self._scheduler = threading.Thread(target=self._schedule, name="sample-commits")
        self._scheduler.start()
        return self

    def _enqueue(self, target, reason, scheduled_ns, started=None):
        # Caller holds _condition. Never let a backlog hide missed sample times.
        if self._pending >= 16:
            raise RuntimeError("Commit-probe sampler saturated before its next scheduled attempt")
        self._pending += 1
        future = self._pool.submit(_sample, self.topology, target, scheduled_ns, reason,
                                   self.timeout_s, started)
        future.add_done_callback(self._record)

    def _record(self, future):
        with self._condition:
            self._pending -= 1
            try:
                row = future.result()
                row.update(interval_s=self.interval_s, sql_timeout_s=self.timeout_s)
                self._output.write(json.dumps(row, separators=(",", ":")) + "\n")
                self._output.flush()
                os.fsync(self._output.fileno())
                if row["status"] == "verified":
                    self._verified[row["target"]] = row
            except Exception as exc:
                self._error = exc
                self._stop.set()
            self._condition.notify_all()

    def _schedule(self):
        deadline = time.perf_counter_ns()
        while not self._stop.wait(max(0, deadline - time.perf_counter_ns()) / 1e9):
            with self._condition:
                try:
                    self._enqueue(self._target, "cadence", deadline)
                except Exception as exc:
                    self._error = exc
                    self._condition.notify_all()
                    self._stop.set()
                    return
            deadline += int(self.interval_s * 1e9)

    def wait_verified(self, target, timeout=30):
        with self._condition:
            ready = self._condition.wait_for(lambda: self._error or target in self._verified, timeout)
            if self._error:
                raise RuntimeError("Commit-probe sampling failed") from self._error
            if not ready:
                raise TimeoutError(f"No independently verified commit on {target} in {timeout}s")
            return self._verified[target].copy()

    def route(self, target):
        if target != "standby" or self._target != "primary":
            raise ValueError("Expected one route change from primary to promoted standby")
        # Also cover switches faster than one sampling period. Neither SQL result gates routing.
        started = threading.Event()
        with self._condition:
            self._enqueue(self._target, "route-check", time.perf_counter_ns(), started)
            if not started.wait(self.timeout_s):
                raise TimeoutError("Old endpoint route check could not start")
            self._target = target
            self._enqueue(target, "route-start", time.perf_counter_ns())

    def __exit__(self, exc_type, exc, traceback):
        self._stop.set()
        self._scheduler.join()
        self._pool.shutdown(wait=True)
        self._output.close()
        if exc is None and self._error:
            raise RuntimeError("Commit-probe sampling failed") from self._error
