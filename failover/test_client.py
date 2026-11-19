import json
import os
import shlex
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from client import Ledger, Worker, WorkerError


# Runs out of process. Its first check observes the external ledger before it replies.
FIXTURE = '''
import json, os, sys, time
from pathlib import Path

def emit(event, **fields):
    print(json.dumps(dict(event=event, **fields)), flush=True)

emit("ready", pid=os.getpid())
for line in sys.stdin:
    req = json.loads(line)
    rows = [json.loads(row) for row in Path(req["ledger"]).read_text().splitlines()]
    assert rows[-1]["event"] == "send" and rows[-1]["request"] == req
    cmd = req["command"]
    if req.get("checkpoint"):
        emit(req["checkpoint"], command=cmd, pid=os.getpid())
        time.sleep(60)
    elif req.get("failure") == "db":
        emit("error", message="connection lost")
    else:
        receipt = dict(commandId=cmd, kind="SUBMIT", payload="...", outcome="QUEUED",
                       detail="", batchId=None, revision=1, gross="0")
        emit("ack", command=cmd if req.get("failure") != "identity" else "wrong", receipt=receipt)
'''


class ExternalClientTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="failover client ")
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        script = root / "worker.py"
        script.write_text(FIXTURE, encoding="utf-8")
        if os.name == "nt":
            self.launcher = root / "fixture.bat"
            self.launcher.write_text(f'@"{sys.executable}" "%~dp0worker.py" %*\n', encoding="utf-8")
        else:
            self.launcher = root / "fixture"
            self.launcher.write_text(
                f'#!/bin/sh\nexec {shlex.quote(sys.executable)} {shlex.quote(str(script))} "$@"\n',
                encoding="utf-8",
            )
            self.launcher.chmod(0o755)
        self.path = root / "ledger.jsonl"
        self.ledger = self.enterContext(Ledger(self.path))
        self.request = {"op": "submit", "command": "cmd", "ledger": str(self.path)}

    def worker(self, name):
        return self.enterContext(Worker(name, self.launcher, "jdbc:postgresql://unused/test", self.ledger))

    def rows(self):
        return [json.loads(line) for line in self.path.read_text(encoding="utf-8").splitlines()]

    def test_checkpoint_lost_and_retry_confirmed_by_different_process(self):
        a, b = self.worker("a"), self.worker("b")
        self.assertNotEqual(a.pid, b.pid)
        with patch("client.os.fsync", wraps=os.fsync) as sync:
            first = a.send({**self.request, "checkpoint": "afterCommit"})
            self.assertEqual(a.read()["event"], "afterCommit")
            with self.assertRaises(TimeoutError):
                a.read(timeout=0.01)
            self.assertEqual([row["event"] for row in self.rows() if "attempt" in row], ["send", "checkpoint"])
            a.kill("cut-reply")
            a.close()
            second = b.send(self.request)
            ack = b.read()
            self.assertEqual(sync.call_count, 6)
        complete = self.rows()
        self.assertEqual([r["pid"] for r in complete if r["event"] == "worker_ready"], [a.pid, b.pid])
        exited, = [r for r in complete if r["event"] == "worker_exit"]
        self.assertEqual(exited["pid"], a.pid)
        self.assertTrue(exited["forced"])
        rows = [r for r in complete if "attempt" in r]
        self.assertEqual([r["event"] for r in rows], ["send", "checkpoint", "unknown", "send", "ack"])
        self.assertEqual([r["attempt"] for r in rows], [first] * 3 + [second] * 2)
        self.assertNotEqual(first, second)
        self.assertEqual(rows[2]["reason"], "cut-reply")
        self.assertEqual(ack["receipt"], rows[-1]["response"]["receipt"])
        self.assertLess(exited["seq"], rows[-2]["seq"])
        self.assertEqual([r["seq"] for r in complete], list(range(1, len(complete) + 1)))
        self.assertEqual([r["t_ns"] for r in complete], sorted(r["t_ns"] for r in complete))

    def test_incorrect_ack_identity_and_sql_error_never_count_as_ack(self):
        a, b = self.worker("a"), self.worker("b")
        with self.assertRaisesRegex(WorkerError, "full matching receipt"):
            a.request({**self.request, "failure": "identity"})
        with self.assertRaisesRegex(WorkerError, "connection lost"):
            b.request({**self.request, "failure": "db"})
        self.assertEqual([r["event"] for r in self.rows() if "attempt" in r],
                         ["send", "unknown", "send", "error", "unknown"])

    def test_exclusive_evidence_file_and_mark(self):
        with self.assertRaises(FileExistsError):
            Ledger(self.path)
        stamp = self.ledger.mark("fault", role="primary")
        self.assertEqual(self.rows(), [{"seq": 1, "t_ns": stamp, "event": "mark",
                                       "label": "fault", "role": "primary"}])


if __name__ == "__main__":
    unittest.main()
