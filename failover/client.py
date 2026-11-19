"""External command ledger and client for independent settlement-lab JVMs."""

import json
import os
import queue
import signal
import subprocess
import tempfile
import threading
import time
import uuid
from pathlib import Path


def now_ns():
    return time.perf_counter_ns()


def _encode(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False)


class Ledger:
    """One host clock and one fsynced order across all participating workers."""

    def __init__(self, path):
        self.path = Path(path)
        self._file = self.path.open("x", encoding="utf-8", newline="\n")
        self._lock = threading.Lock()
        self._seq = 0

    def record(self, event, **fields):
        if {"seq", "t_ns", "event"} & fields.keys():
            raise ValueError("Reserved ledger fields")
        with self._lock:
            observed = now_ns()
            row = dict(seq=self._seq + 1, t_ns=observed, event=event, **fields)
            self._file.write(_encode(row) + "\n")
            self._file.flush()
            os.fsync(self._file.fileno())
            self._seq += 1
            return observed

    def mark(self, label, **fields):
        return self.record("mark", label=label, **fields)

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        with self._lock:
            self._file.close()


class WorkerError(RuntimeError):
    pass


_TERMINAL = {"migrate": "ok", "init": "ok", "snapshot": "snapshot", "receipt": "receipt",
             "submit": "ack", "cancel": "ack", "settle": "ack"}
_RECEIPT_FIELDS = {"commandId", "kind", "payload", "outcome", "detail", "batchId", "revision", "gross"}


class Worker:
    """One serial request stream. Its reader durably records replies even between polls."""

    def __init__(self, name, launcher, jdbc_url, ledger):
        if not jdbc_url or not jdbc_url.startswith("jdbc:postgresql:"):
            raise ValueError("An explicit PostgreSQL JDBC URL is required")
        self.name = name
        self.ledger = ledger
        self.last_event_ns = None
        self._events = queue.Queue()
        self._lock = threading.Lock()
        self._pending = None
        self._busy = False
        self._closed = False
        self._end_reason = "worker-output-closed"
        self._stderr = tempfile.TemporaryFile()
        env = {**os.environ, "SETTLEMENT_JDBC_URL": jdbc_url,
               "SETTLEMENT_DB_USER": "settlement_lab", "SETTLEMENT_DB_PASSWORD": "settlement_lab"}
        try:
            self._proc = subprocess.Popen(
                [str(launcher), "pg", "worker"], env=env, stdin=subprocess.PIPE,
                stdout=subprocess.PIPE, stderr=self._stderr, text=True, encoding="utf-8",
                start_new_session=os.name != "nt", bufsize=1,
            )
        except BaseException:
            self._stderr.close()
            raise
        self._reader = threading.Thread(target=self._receive, name=f"receipt-{name}", daemon=True)
        self._reader.start()
        try:
            hello = self.read(timeout=30)
            if hello.get("event") != "ready" or type(hello.get("pid")) is not int or hello["pid"] <= 0:
                raise WorkerError(f"{name} did not send a valid startup frame")
            self.pid = hello["pid"]
            self.ledger.record("worker_ready", worker=name, pid=self.pid,
                               launcher_pid=self._proc.pid, endpoint=jdbc_url)
        except BaseException:
            self.kill("startup-failed")
            raise

    def send(self, request):
        """Persist the exact input before making it available to the application."""
        encoded = _encode(request)
        request = json.loads(encoded)
        if not isinstance(request, dict) or request.get("op") not in _TERMINAL:
            raise ValueError("Unrecognized worker operation")
        with self._lock:
            if self._busy or self._closed or not self._reader.is_alive() or self._proc.poll() is not None:
                raise WorkerError(f"{self.name} is busy or stopped")
            attempt = str(uuid.uuid4())
            self.ledger.record("send", attempt=attempt, worker=self.name, request=request)
            self._pending = attempt, request
            self._busy = True
            try:
                self._proc.stdin.write(encoded + "\n")
                self._proc.stdin.flush()
            except OSError as exc:
                self._unknown_locked("write-failed")
                raise WorkerError(f"{self.name} write failed") from exc
            return attempt

    def read(self, timeout=40):
        """A polling timeout leaves the attempt outstanding."""
        try:
            frame, stamp = self._events.get(timeout=timeout)
        except queue.Empty:
            raise TimeoutError(f"{self.name} produced no frame in {timeout}s") from None
        self.last_event_ns = stamp
        if isinstance(frame, Exception):
            raise frame
        if frame["event"] not in ("beforeCommit", "afterCommit"):
            with self._lock:
                self._busy = False
        if frame["event"] == "error":
            raise WorkerError(f"{self.name} returned {frame}")
        return frame

    def request(self, request, timeout=40):
        self.send(request)
        frame = self.read(timeout)
        if frame["event"] in ("beforeCommit", "afterCommit"):
            raise WorkerError("Use send() and read() to drive a checkpoint")
        return frame

    def _unknown_locked(self, reason):
        if self._pending is None:
            return None
        attempt, _ = self._pending
        stamp = self.ledger.record("unknown", attempt=attempt, worker=self.name, reason=reason)
        self._pending = None
        return stamp

    def _receive(self):
        try:
            hello = json.loads(self._proc.stdout.readline())
            if not isinstance(hello, dict) or hello.get("event") != "ready":
                raise WorkerError("Expected worker startup frame")
            self._events.put((hello, now_ns()))
            for line in self._proc.stdout:
                frame = json.loads(line)
                if not isinstance(frame, dict):
                    raise WorkerError("Expected worker response object")
                with self._lock:
                    self._record_frame(frame)
            raise EOFError(self._end_reason)
        except Exception as exc:
            try:
                with self._lock:
                    stamp = self._unknown_locked(self._end_reason)
            except OSError as ledger_error:
                exc, stamp = ledger_error, None
            # The worker may emit Flyway/JVM diagnostics to stderr on failure.
            self._stderr.seek(0, os.SEEK_END)
            self._stderr.seek(max(0, self._stderr.tell() - 1800))
            detail = self._stderr.read().decode("utf-8", errors="replace").strip()
            self._events.put((WorkerError(f"{self.name}: {exc}. {detail}"), stamp or now_ns()))

    def _record_frame(self, frame):
        if self._pending is None:
            raise WorkerError("Unsolicited worker response")
        attempt, request = self._pending
        event = frame.get("event")
        if event in ("beforeCommit", "afterCommit"):
            if event != request.get("checkpoint") or frame.get("command") != request.get("command"):
                raise WorkerError("Worker checkpoint identity mismatch")
            if event == "afterCommit" and frame.get("pid") != self.pid:
                raise WorkerError("Worker checkpoint process mismatch")
            category = "checkpoint"
        elif event == "error":
            category = "error"
        elif event == _TERMINAL[request["op"]]:
            category = "ack" if event == "ack" else "result"
            if event == "ack":
                receipt = frame.get("receipt")
                if (not isinstance(receipt, dict) or not _RECEIPT_FIELDS <= receipt.keys()
                        or receipt["commandId"] != request.get("command")
                        or frame.get("command") != request.get("command")):
                    raise WorkerError("Worker ACK is missing the full matching receipt")
        else:
            raise WorkerError(f"Unexpected response {event} to {request['op']}")
        stamp = self.ledger.record(category, worker=self.name, attempt=attempt, response=frame)
        if category == "error" and _TERMINAL[request["op"]] == "ack":
            self._unknown_locked("worker-error")
        elif category != "checkpoint":
            self._pending = None
        self._events.put((frame, stamp))

    def _terminate_tree(self):
        if self._proc.poll() is not None:
            return
        if os.name == "nt":
            # The Gradle .bat launcher is cmd.exe with its Java child. Terminate both.
            result = subprocess.run(["taskkill", "/pid", str(self._proc.pid), "/t", "/f"],
                                    capture_output=True, timeout=10)
            if result.returncode and self._proc.poll() is None:
                raise WorkerError(f"Could not stop the {self.name} process tree")
        else:
            try:
                os.killpg(self._proc.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass

    def _finish(self, force, reason):
        if self._closed:
            return
        with self._lock:
            self._end_reason = reason
            force = force or self._pending is not None
        if force:
            self._terminate_tree()
        else:
            self._proc.stdin.close()
        try:
            self._proc.wait(8)
        except subprocess.TimeoutExpired:
            self._terminate_tree()
            self._proc.wait(8)
        self._reader.join(5)
        if self._reader.is_alive():
            raise WorkerError(f"{self.name} stdout is still open after process exit")
        try:
            self._proc.stdin.close()
        except BrokenPipeError:
            pass
        self._proc.stdout.close()
        self._stderr.close()
        self._closed = True
        if hasattr(self, "pid"):
            self.ledger.record("worker_exit", worker=self.name, pid=self.pid, launcher_pid=self._proc.pid,
                               forced=force, reason=reason, launcher_exit_code=self._proc.returncode)

    def kill(self, reason="app-killed"):
        self._finish(True, reason)

    def close(self):
        self._finish(False, "client-closed")

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()
