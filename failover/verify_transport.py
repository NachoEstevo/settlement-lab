"""Offline checks of the physical failover and the client's commit probes."""

import re
from collections import Counter
from uuid import UUID


def require(condition, message):
    if not condition:
        raise ValueError(message)


def number(value):
    require(type(value) is int and value >= 0, f"expected a nonnegative integer, got {value!r}")
    return value


def uuid(value):
    require(isinstance(value, str) and str(UUID(value)) == value, f"invalid UUID: {value!r}")
    return value


def lsn(value):
    require(isinstance(value, str) and re.fullmatch(r"[0-9A-F]{1,8}/[0-9A-F]{1,8}", value),
            f"invalid WAL LSN: {value!r}")
    high, low = value.split("/")
    return (int(high, 16) << 32) + int(low, 16)


def _ordered(values, label):
    times = [number(value) for value in values]
    require(times == sorted(times), f"invalid {label} order")


def check_topology(mode, topo, timeline):
    initial = topo["initial"]
    primary, standby = initial["primary"], initial["standby"]
    require(initial["mode"] == mode and primary["in_recovery"] is False
            and standby["in_recovery"] is True, "wrong initial roles or replication mode")
    cluster = primary["system_identifier"]
    require(cluster.isdigit() and cluster == standby["system_identifier"], "different physical clusters")
    promotion = topo["promotion"]
    promoted = promotion["new_primary"]
    require(primary["image"] == standby["image"] and re.fullmatch(r".+@sha256:[0-9a-f]{64}", primary["image"]),
            "physical cluster images are not pinned to the same digest")
    require(number(primary["timeline"]) == number(standby["timeline"]) < number(promoted["timeline"]),
            "promotion did not branch to a new timeline")
    for key in ("container_id", "endpoint"):
        require(primary[key] != standby[key] == promoted[key], "promotion did not preserve standby identity")
    for node in [primary, standby, promoted]:
        require(node["system_identifier"] == cluster and number(node["server_version_num"]) // 10000 == 18,
                "unexpected PostgreSQL cluster or major version")
        require(node["fsync"] == "on" and node["synchronous_commit"] == "on"
                and node["archive_mode"] == "off" and node["restore_command"] == "",
                "unexpected commit durability or alternative WAL source")
    require(standby["synchronous_standby_names"] == promoted["synchronous_standby_names"] == ""
            and promoted["in_recovery"] is False, "incorrect standalone promotion")
    names = "FIRST 1 (settlement_standby)" if mode == "sync" else ""
    require(primary["synchronous_standby_names"] == names, "incorrect original sync policy")
    barrier = topo["barrier"]
    for sender in [initial["sender"], barrier["sender"]]:
        require(sender["user"] == "settlement_replica" and sender["name"] == "settlement_standby"
                and sender["state"] == "streaming" and sender["sync_state"] == mode,
                "unexpected replication sender")
    target = lsn(barrier["primary_flush_lsn"])
    require(target <= lsn(barrier["standby_flush_lsn"]) == lsn(barrier["sender"]["flush_lsn"])
            and target <= lsn(barrier["standby_replay_lsn"]), "baseline WAL not flushed and replayed")
    cut, final = topo["cut"], topo["cut_before_fence"]
    for sample in [cut, final]:
        require(sample["replication_login"] is False and sample["in_recovery"] is True
                and number(sample["sender_count"]) == number(sample["receiver_count"]) == 0,
                "WAL transport remained available")
        require(target <= lsn(sample["standby_receive_lsn"]) <= lsn(sample["standby_replay_lsn"]),
                "received WAL excludes baseline or was not replayed")
    require(lsn(cut["standby_receive_lsn"]) == lsn(final["standby_receive_lsn"])
            < lsn(final["primary_flush_lsn"]), "suffix not beyond a stationary physical receive cutoff")
    require(target <= lsn(cut["primary_flush_lsn"]) <= lsn(final["primary_flush_lsn"]),
            "primary WAL flush position regressed")
    fence = topo["fence"]
    reprobe = promotion["old_primary"]
    require(primary["container_id"] == fence["container_id"] == reprobe["container_id"]
            and primary["endpoint"] == fence["old_endpoint"] == reprobe["old_endpoint"],
            "fence does not identify the original primary")
    for record in [fence, reprobe]:
        require(record["status"] == "exited" and record["running"] is False and record["restart_policy"] == "no"
                and number(record["exit_code"]) == 137 and number(record["restart_count"]) == 0
                and record["endpoint_probe"] in {"connection_closed", "connection_reset", "connection_refused"},
                "old primary fence was not established")
    _ordered([timeline["fault_ns"], fence["started_ns"], fence["verified_ns"], timeline["fenced_ns"],
              timeline["promotion_start_ns"], promotion["started_ns"], promotion["promoted_ns"],
              timeline["promotion_end_ns"]], "fence and promotion")


def check_probes(probes, trial):
    timeline = trial["timeline"]
    fault = trial["topology"]["fence"]["started_ns"]
    promotion = trial["topology"]["promotion"]
    ids, payloads = set(), set()
    statuses = Counter()
    verified = []
    cadence = []
    routes = {}
    require(probes, "missing commit probes")
    interval = probes[0]["interval_s"]
    require(type(interval) in (float, int) and interval > 0, "invalid probe interval")
    for row in probes:
        probe_id, payload = uuid(row["probe_id"]), uuid(row["expected"])
        require(probe_id not in ids and payload not in payloads and probe_id != payload, "reused probe identity or payload")
        ids.add(probe_id)
        payloads.add(payload)
        require(row["target"] in {"primary", "standby"}, "unknown probe target")
        status = row["status"]
        require(status in {"verified", "unknown", "unverified", "failed"}, "unknown probe status")
        statuses[status] += 1
        require(row["interval_s"] == interval, "changed probe interval")
        if row["reason"] == "cadence":
            cadence.append(number(row["scheduled_ns"]))
        else:
            reason = row["reason"]
            require(reason in {"route-check", "route-start"} and reason not in routes,
                    "unexpected or repeated probe route transition")
            routes[reason] = row
        times = [row["scheduled_ns"], row["start_ns"]]
        if row["ack_ns"] is not None:
            times.append(row["ack_ns"])
            if row["target"] == "primary":
                require(row["start_ns"] < timeline["fenced_ns"], "fresh commit acknowledged by fenced primary")
                require(trial["mode"] != "sync" or row["start_ns"] < timeline["cut_end_ns"],
                        "fresh synchronous commit acknowledged after required standby was cut")
        if row["observed_ns"] is not None:
            times.append(row["observed_ns"])
        _ordered([*times, row["end_ns"]], "probe")
        if status == "verified":
            require(row["ack_ns"] is not None and row["observed_ns"] is not None and row["observed"] == payload,
                    "verified probe lacks a matching ACK and independent readback")
            verified.append(row)
            require(row["target"] != "primary" or row["start_ns"] < timeline["fenced_ns"],
                    "verified new write to an already fenced primary")
        elif status == "unverified":
            require(row["ack_ns"] is not None and row["observed"] != payload, "invalid unverified readback")
        else:
            require(row["ack_ns"] is None and row["observed"] is None, "unsuccessful commit claims a result")
        if row["target"] == "standby":
            require(row["start_ns"] >= timeline["promotion_end_ns"], "probe routed to standby before promotion")
    cadence.sort()
    require(len(cadence) >= 2 and all(b - a == int(interval * 1e9) for a, b in zip(cadence, cadence[1:]))
            and cadence[0] <= timeline["cut_start_ns"] and cadence[-1] + int(interval * 1e9) >= timeline["route_switch_ns"],
            "incomplete probe cadence through cutover")
    require(set(routes) == {"route-check", "route-start"} and routes["route-check"]["target"] == "primary"
            and routes["route-start"]["target"] == "standby" and timeline["route_switch_ns"]
            <= routes["route-check"]["start_ns"] <= routes["route-start"]["start_ns"], "incomplete direct routing probe")
    old = [p for p in verified if p["target"] == "primary"]
    new = [p for p in verified if p["target"] == "standby"]
    require(old and new, "missing verified commits bracketing primary failure")
    require(any(p["ack_ns"] <= fault for p in old), "missing successful pre-fault probe")
    last = max(old, key=lambda p: p["ack_ns"])
    first = min(new, key=lambda p: p["ack_ns"])
    require(last["ack_ns"] < first["ack_ns"], "probe ACKs do not bound the observed outage")
    require(any(p["target"] == "primary" and p["status"] != "verified"
                and timeline["cut_end_ns"] <= p["start_ns"] <= first["ack_ns"] for p in probes),
            "missing unsuccessful real write probe during transition")
    return {"probe_count_by_status": dict(sorted(statuses.items())), "last_primary_probe_id": last["probe_id"],
            "first_promoted_probe_id": first["probe_id"], "last_primary_ack_ns": last["ack_ns"],
            "first_promoted_ack_ns": first["ack_ns"], "commit_ack_gap_ns": first["ack_ns"] - last["ack_ns"],
            "rto_ns": first["ack_ns"] - fault,
            "promotion_ns": promotion["promoted_ns"] - promotion["started_ns"],
            "first_promoted_readback_ns": first["observed_ns"] - first["ack_ns"]}
