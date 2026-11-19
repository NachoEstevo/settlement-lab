"""Disposable two-node PostgreSQL topology for the failover experiment."""

import ipaddress
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import tempfile
import time
import uuid


HERE = Path(__file__).resolve().parent
STANDBY = "settlement_standby"
SYNC_NAMES = f"FIRST 1 ({STANDBY})"


def _run(args, timeout=15):
    result = subprocess.run(args, capture_output=True, text=True, encoding="utf-8",
                            errors="replace", timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"{args[0]} exited {result.returncode}: "
                           f"{result.stderr.strip() or result.stdout.strip()}")
    return result.stdout.strip()


def _wait(description, sample, valid, timeout=30):
    deadline = time.monotonic() + timeout
    while True:
        value = sample()
        if valid(value):
            return value
        if time.monotonic() >= deadline:
            raise RuntimeError(f"Timed out waiting for {description}; last observation: {value}")
        time.sleep(0.1)


def _probe(port):
    """Probe the published JDBC endpoint with PostgreSQL's SSLRequest packet."""
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=3) as connection:
            connection.sendall(struct.pack("!II", 8, 80877103))
            reply = connection.recv(1)
            if reply in (b"N", b"S"):
                return "postgres_responded"
            if not reply:
                return "connection_closed"
            raise RuntimeError(f"Unexpected endpoint response on {port}: {reply!r}")
    except ConnectionRefusedError:
        return "connection_refused"
    except ConnectionResetError:
        return "connection_reset"


def lsn_bytes(lsn):
    high, low = lsn.split("/")
    return (int(high, 16) << 32) + int(low, 16)


class Topology:
    def __init__(self, mode, subnet=None):
        if mode not in ("async", "sync"):
            raise ValueError(f"Unknown replication mode {mode!r}")
        self.mode = mode
        self.project = "settlement-failover-" + uuid.uuid4().hex
        subnet = subnet or os.environ.get("SETTLEMENT_FAILOVER_SUBNET")
        self.subnet = str(ipaddress.IPv4Network(subnet)) if subnet else None
        self._compose = ["docker", "compose", "-p", self.project, "-f", str(HERE / "compose.yaml")]
        self._containers = {}
        self._ports = {}
        self._fence = None

    def __enter__(self):
        if hasattr(self, "_scratch"):
            raise RuntimeError("Create a fresh Topology for each experiment")
        self._scratch = tempfile.TemporaryDirectory(prefix=self.project + "-")
        try:
            if self.subnet:
                override = Path(self._scratch.name) / "network.json"
                override.write_text(json.dumps({"networks": {"default": {"ipam": {
                    "config": [{"subnet": self.subnet}]}}}}), encoding="utf-8")
                self._compose += ["-f", str(override)]
            _run([*self._compose, "up", "-d", "--wait", "--wait-timeout", "100"], timeout=150)
            for node in ("primary", "standby"):
                self._containers[node] = _run([*self._compose, "ps", "-q", node])
                info = self._inspect(node)
                if info["HostConfig"]["RestartPolicy"]["Name"] != "no":
                    raise RuntimeError(f"Unexpected restart policy for {node}")
                bindings = info["NetworkSettings"]["Ports"]["5432/tcp"]
                if len(bindings) != 1 or bindings[0]["HostIp"] != "127.0.0.1":
                    raise RuntimeError(f"Unexpected published PostgreSQL port: {bindings}")
                port = int(bindings[0]["HostPort"])
                self._ports[node] = port
                if _probe(port) != "postgres_responded":
                    raise RuntimeError(f"Unreachable {node} endpoint on port {port}")
            if self.mode == "sync":
                # Apply only to the primary, after the standby's base backup.
                self.sql("primary", f"ALTER SYSTEM SET synchronous_standby_names = '{SYNC_NAMES}'")
                self.sql("primary", "SELECT pg_reload_conf()")
            _wait("named streaming standby", self._sender, self._expected_sender)
            self._initial = self.describe()
            return self
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def __exit__(self, exc_type, exc, traceback):
        down = [*self._compose, "down", "--volumes", "--timeout", "3"]
        try:
            try:
                _run(down, timeout=60)
            except (RuntimeError, subprocess.TimeoutExpired):
                # Removal is idempotent even if Docker already removed some project resources.
                _run(down, timeout=60)
        finally:
            self._scratch.cleanup()

    def _inspect(self, node):
        info, = json.loads(_run(["docker", "inspect", self._containers[node]]))
        labels = info["Config"]["Labels"]
        if (labels.get("com.docker.compose.project") != self.project
                or labels.get("com.docker.compose.service") != node):
            raise RuntimeError(f"Container identity mismatch for {self.project}/{node}")
        return info

    def _url(self, node):
        return f"jdbc:postgresql://127.0.0.1:{self._ports[node]}/settlement_lab"

    @property
    def primary_url(self):
        return self._url("primary")

    @property
    def standby_url(self):
        return self._url("standby")

    def psql_command(self, node, *, client_node=None):
        if node not in self._containers:
            raise ValueError(f"Unknown database endpoint {node!r}")
        client = client_node or node
        host = node if client_node else "127.0.0.1"
        return ["docker", "exec", "-e", "PGPASSWORD=settlement_lab",
                "-e", "PGCONNECT_TIMEOUT=2", self._containers[client],
                "psql", "-XAtq", "-w", "-v", "ON_ERROR_STOP=1", "-h", host,
                "-p", "5432", "-U", "settlement_lab", "-d", "settlement_lab"]

    def sql(self, node, query, timeout=15):
        return _run([*self.psql_command(node), "-c", query], timeout=timeout)

    def _sender(self):
        return json.loads(self.sql("primary", """
            SELECT coalesce(json_agg(json_build_object('name', application_name,
                'user', usename, 'state', state, 'sync_state', sync_state,
                'sent_lsn', sent_lsn, 'write_lsn', write_lsn, 'flush_lsn', flush_lsn,
                'replay_lsn', replay_lsn)), '[]') FROM pg_stat_replication
            """))

    def _expected_sender(self, rows):
        expected = "sync" if self.mode == "sync" else "async"
        return (len(rows) == 1 and rows[0]["name"] == STANDBY
                and rows[0]["user"] == "settlement_replica" and rows[0]["state"] == "streaming"
                and rows[0]["sync_state"] == expected and rows[0]["flush_lsn"] is not None)

    def _settings(self, node):
        return json.loads(self.sql(node, """
            SELECT json_build_object('system_identifier', system_identifier::text,
                'server_version', current_setting('server_version'),
                'server_version_num', current_setting('server_version_num')::int,
                'in_recovery', pg_is_in_recovery(), 'fsync', current_setting('fsync'),
                'timeline', CASE WHEN pg_is_in_recovery()
                    THEN (SELECT received_tli FROM pg_stat_wal_receiver)
                    ELSE ('x' || left(pg_walfile_name(pg_current_wal_lsn()), 8))::bit(32)::int END,
                'synchronous_commit', current_setting('synchronous_commit'),
                'synchronous_standby_names', current_setting('synchronous_standby_names'),
                'archive_mode', current_setting('archive_mode'),
                'restore_command', current_setting('restore_command'))
            FROM pg_control_system()
            """))

    def describe(self):
        primary = self._settings("primary")
        standby = self._settings("standby")
        if (primary["in_recovery"] or not standby["in_recovery"]
                or primary["system_identifier"] != standby["system_identifier"]
                or primary["timeline"] != standby["timeline"]
                or primary["synchronous_standby_names"] != (SYNC_NAMES if self.mode == "sync" else "")
                or standby["synchronous_standby_names"] != ""
                or any(n["server_version_num"] // 10000 != 18 or n["fsync"] != "on"
                       or n["synchronous_commit"] != "on" or n["archive_mode"] != "off"
                       or n["restore_command"] != "" for n in (primary, standby))):
            raise RuntimeError(f"Unexpected physical topology: primary={primary}, standby={standby}")
        sender = self._sender()
        if not self._expected_sender(sender):
            raise RuntimeError(f"Unexpected WAL sender: {sender}")
        for node, settings in (("primary", primary), ("standby", standby)):
            info = self._inspect(node)
            settings.update(container_id=info["Id"], image=info["Config"]["Image"],
                            endpoint=self._url(node))
        return {"project": self.project, "mode": self.mode, "primary": primary,
                "standby": standby, "sender": sender[0]}

    def barrier(self):
        """Wait for both primary-observed standby flush and standby replay of a fixed LSN."""
        target = self.sql("primary", "SELECT pg_current_wal_flush_lsn()")
        sender, = _wait("standby flush through " + target, self._sender,
                        lambda rows: self._expected_sender(rows)
                        and lsn_bytes(rows[0]["flush_lsn"]) >= lsn_bytes(target))
        replay = _wait("standby replay through " + target,
                       lambda: self.sql("standby", "SELECT pg_last_wal_replay_lsn()"),
                       lambda lsn: lsn and lsn_bytes(lsn) >= lsn_bytes(target))
        return {"primary_flush_lsn": target, "standby_flush_lsn": sender["flush_lsn"],
                "standby_replay_lsn": replay, "sender": sender}

    def cut(self):
        """Deny future replication logins, then disconnect the sole existing WAL sender."""
        before = self._sender()
        if not self._expected_sender(before):
            raise RuntimeError(f"Unexpected sender before cut: {before}")
        self.sql("primary", "ALTER ROLE settlement_replica NOLOGIN")
        terminated = self.sql("primary", """
            SELECT pg_terminate_backend(pid, 5000) FROM pg_stat_replication
            WHERE application_name = 'settlement_standby' AND usename = 'settlement_replica'
            """)
        if terminated != "t":
            raise RuntimeError(f"Expected one disconnected replication backend: {terminated!r}")
        return self.cut_status()

    def cut_status(self):
        """Recheck transport and read the WAL physically received after disconnect."""
        primary = json.loads(self.sql("primary", """
            SELECT json_build_object('replication_login', rolcanlogin,
                'sender_count', (SELECT count(*) FROM pg_stat_replication),
                'primary_flush_lsn', pg_current_wal_flush_lsn())
            FROM pg_roles WHERE rolname = 'settlement_replica'
            """))
        if primary["replication_login"] or primary["sender_count"] != 0:
            raise RuntimeError(f"WAL transport is enabled: {primary}")

        def receiver():
            return json.loads(self.sql("standby", """
                SELECT json_build_object('receiver_count', (SELECT count(*) FROM pg_stat_wal_receiver),
                    'standby_receive_lsn', pg_last_wal_receive_lsn(),
                    'standby_replay_lsn', pg_last_wal_replay_lsn(),
                    'in_recovery', pg_is_in_recovery())
                """))
        standby = _wait("disconnected WAL receiver and replay of received WAL", receiver,
                        lambda r: r["receiver_count"] == 0 and r["in_recovery"]
                        and r["standby_receive_lsn"] is not None and r["standby_replay_lsn"] is not None
                        and lsn_bytes(r["standby_replay_lsn"]) >= lsn_bytes(r["standby_receive_lsn"]))
        return {**primary, **standby}

    def _fence_evidence(self):
        info = self._inspect("primary")
        state = info["State"]
        restart = info["HostConfig"]["RestartPolicy"]["Name"]
        if (state["Running"] or state["Status"] != "exited" or restart != "no"
                or state["ExitCode"] != 137 or state["OOMKilled"] or info["RestartCount"] != 0):
            raise RuntimeError(f"Old primary is not hard-fenced: {state}, restart={restart}")
        outcome = _wait("refusal by the former primary endpoint", lambda: _probe(self._ports["primary"]),
                        lambda result: result != "postgres_responded", timeout=5)
        return {"container_id": info["Id"], "running": state["Running"], "status": state["Status"],
                "exit_code": state["ExitCode"], "restart_policy": restart,
                "restart_count": info["RestartCount"], "old_endpoint": self.primary_url,
                "endpoint_probe": outcome}

    def fence(self):
        info = self._inspect("primary")
        if not info["State"]["Running"] or info["HostConfig"]["RestartPolicy"]["Name"] != "no":
            raise RuntimeError("Expected running primary with Docker restart disabled")
        if _probe(self._ports["primary"]) != "postgres_responded":
            raise RuntimeError("Old primary did not answer immediately before the fence")
        started = time.perf_counter_ns()
        _run(["docker", "kill", "--signal", "KILL", info["Id"]], timeout=20)
        proof = self._fence_evidence()
        self._fence = {**proof, "started_ns": started, "verified_ns": time.perf_counter_ns()}
        return self._fence.copy()

    def promote(self):
        if self._fence is None:
            raise RuntimeError("Promotion requires a verified hard fence")
        proof = self._fence_evidence()
        if self.sql("standby", "SELECT pg_is_in_recovery()") != "t":
            raise RuntimeError("Promotion requires a standby in recovery")
        started = time.perf_counter_ns()
        if self.sql("standby", "SELECT pg_promote(true, 10)") != "t":
            raise RuntimeError("Standby did not complete promotion")
        promoted = time.perf_counter_ns()
        settings = self._settings("standby")
        settings.update(container_id=self._containers["standby"], endpoint=self.standby_url)
        origin = self._initial["standby"]
        if (settings["system_identifier"] != origin["system_identifier"]
                or settings["timeline"] <= origin["timeline"]
                or settings["in_recovery"] or settings["synchronous_standby_names"] != ""
                or settings["fsync"] != "on" or settings["synchronous_commit"] != "on"):
            raise RuntimeError(f"Unexpected promoted standalone primary: {settings}")
        return {"started_ns": started, "promoted_ns": promoted, "old_primary": proof,
                "new_primary": settings}
