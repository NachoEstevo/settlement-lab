# PostgreSQL failover and client receipts

The experiment asks two questions. Which commands had the client seen acknowledged when the primary failed? Which of those commands, payments and balance changes could a client recover from the promoted standby **before sending any command again**? An append-only ledger on the client host supplies the first answer. A history exported from the promoted PostgreSQL server supplies the second.

## Results from six physical failovers

In all three async trials, the promoted primary was missing one of six acknowledged commands. The client had received a receipt for a 7-unit settlement that never reached the standby. All five acknowledged commands survived in each of the three sync trials. The sync primary had kept the 7-unit settlement waiting for the missing standby and the client had received no ACK for it.

| Client measurement | Async, 3 trials | Sync, 3 trials |
| --- | ---: | ---: |
| Acknowledged case commands missing before retries | 3 of 18 | 0 of 15 |
| Unknown case commands present before retries | 3 of 6 | 3 of 6 |
| Gap between verified SQL commit ACKs, s | 8.833 [8.523–8.896] | 9.389 [9.345–9.676] |
| Primary kill to verified SQL commit ACK, s | 8.473 [8.395–8.516] | 7.351 [7.167–7.573] |
| Primary kill to verified settlement ACK, s | 8.903 [8.785–8.941] | 7.765 [7.622–8.086] |
| PostgreSQL promotion command, s | 3.211 [2.923–3.441] | 2.132 [1.926–2.214] |

Times are medians with the observed minimum and maximum in brackets. The sync client spent 1.467–1.476 s without an ACK after sending the blocked command and before the primary kill. Two samples found its exact PostgreSQL backend in `SyncRep`. The measured lower bound on this wait ranged from 1.009 to 1.014 s. In each mode, the outstanding pre-cut submission survived and the post-cut command with unknown outcome was absent. The pre-retry snapshot of the settlement case showed A at 75 and B at 25. After retries, A had 82, B had 18 and the 7-unit payment belonged to the intended batch.

The trial data records each domain, probe count, individual timing and uncertainty interval in milliseconds. For example, the second JVM resolved the first interrupted cancellation by lookup in 0.173–0.341 s across all six trials. [Per-trial measurements](failover-samples.csv)

The Windows campaign's raw evidence archive is `failover-v02-evidence.zip`, SHA-256 `c5da7517be44243424446a40ad8854c9242e39b4d1efb2e18535166153ee52f9`. Locate the archive with the v0.2.0 release assets, extract it alongside the application, and pass its inner `failover-evidence/` directory to the verifier. [Private releases](https://github.com/NachoEstevo/settlement-lab/releases)

## Reproduce the experiment

Use Java 21, Python 3 (tested with 3.13) and Docker with Compose and Linux containers. From the root of an extracted Settlement Lab distribution, choose a new directory for the evidence.

```sh
python failover/run.py --mode both --repetitions 3 --out failover-evidence
python failover/verify.py failover-evidence
```

From a checkout, run `./gradlew installDist` on Linux or `.\gradlew.bat installDist` on Windows first. Use `--mode async` or `--mode sync` to run one policy. Use `--launcher PATH` to select a particular application launcher. The first topology startup may need to download the pinned PostgreSQL image.

Every trial creates a separate Compose project, a fresh primary volume and a fresh standby volume. Docker assigns separate host ports on loopback. On a Docker engine that has exhausted its automatic address pools, pass `--subnet` with an unused CIDR, or set `SETTLEMENT_FAILOVER_SUBNET`. Allocate different, non-overlapping subnets for harnesses running concurrently. The harness controls its own project, so the single-primary lab on port 55432 can remain running. [Compose project names](https://docs.docker.com/compose/how-tos/project-name/)

## What a successful ACK proves

Both modes use a physical standby cloned with `pg_basebackup`. In asynchronous mode the primary may acknowledge a locally flushed commit before the standby receives its WAL. In synchronous mode the original primary requires the named standby and the application uses `synchronous_commit=on`. A successful COMMIT then waits for the standby to flush the transaction's WAL to durable storage. Applying the transaction to standby tables can happen later. [PostgreSQL 18 streaming replication](https://www.postgresql.org/docs/18/warm-standby.html#STREAMING-REPLICATION) · [Commit durability settings](https://www.postgresql.org/docs/18/runtime-config-wal.html#GUC-SYNCHRONOUS-COMMIT)

The experiment records two independent standby positions. `pg_last_wal_receive_lsn()` measures how far streaming WAL has been received and synced to disk. `pg_last_wal_replay_lsn()` measures how far it has been applied. Pausing replay still allows WAL to reach the standby. A controlled loss experiment therefore has to interrupt transmission, establish a receive cutoff, and show where the primary subsequently flushed its writes. [PostgreSQL 18 recovery functions](https://www.postgresql.org/docs/18/functions-admin.html#FUNCTIONS-RECOVERY-INFO-TABLE)

The client also distinguishes an ACK it received from a commit that completed inside an application whose reply was interrupted. The second case is unknown to the client until a lookup or an identical retry resolves it. A count of lost responses and a count of acknowledged commands absent after promotion answer different questions.

## Faults in each trial

1. Start PostgreSQL 18.7 primary and standby containers from the pinned image. Check that the nodes have the same PostgreSQL system identifier, that only the standby is in recovery, and that the primary sees the named WAL sender. Start two application JVMs. Interrupt one after its database commit but before the client receives the reply. Resend the same request through the other JVM and compare its receipt.
2. Capture a WAL flush position on the primary. Wait for the primary's observed standby flush and the standby's own replay position to reach it. Read the baseline case on the standby and check its entire history against the primary's export.
3. Set the dedicated replication role to `NOLOGIN` and terminate its existing WAL sender. Record sender and receiver counts, received and replayed positions, and the primary's flush position. The containers still run. In async mode the client can receive ACKs for later writes. In sync mode the primary continues requiring `FIRST 1 (settlement_standby)`. The experiment samples the waiting transaction's backend for `SyncRep` while that standby is unavailable. [PostgreSQL 18 replication monitoring and wait events](https://www.postgresql.org/docs/18/monitoring-stats.html)
4. Kill the original primary container. The controller checks its Compose identity, exited state and disabled restart policy, and connects directly to its original published port to check that PostgreSQL can no longer respond. Promotion requires a successful fence and rechecks it. The controller then waits for `pg_promote` to finish on the standby and confirms that it has left recovery. This fence assumes one controller with exclusive control of the disposable project. [Docker restart policies](https://docs.docker.com/engine/containers/start-containers-automatically/) · [PostgreSQL 18 promotion](https://www.postgresql.org/docs/18/functions-admin.html#FUNCTIONS-RECOVERY-CONTROL)
5. Read the business domain on the new primary and retain that export before retrying any of its measured commands. Compare command IDs **and** full receipts, then retry the original requests and export the final history. Probes use independent identifiers so they can measure restored write availability during reconciliation.

The synchronous setting is applied to the original primary **after** the physical backup. Its standby has an empty `synchronous_standby_names`, verified again after promotion. That permits progress with one remaining server and local WAL flush. Newly recovered writes therefore have a different durability condition from pre-fault synchronous ACKs.

## Settlement fixture

A opens with 100 units and B with 0. The client submits an obligation of 30 from A to B, then cancels it. The cancellation is the first interrupted reply and the other application recovers its receipt. A second obligation of 25 from A to B settles. The client also submits an obligation of 7 in the opposite direction and one of 4 from A to B. The reply for the last submission is withheld. At the baseline, A has 75 and B has 25. There is one cancelled payment, one settled payment and two queued payments.

With transmission cut, the client tries to settle B's 7. The async primary can acknowledge the settlement and process another submission of 13 from A to B whose reply is withheld. The synchronous primary must wait for its missing standby when it attempts to settle the 7. The 7-unit settlement changes two balances, one payment's status and batch membership. Losing or replaying it is therefore visible independently of its receipt. After the original requests are replayed, A should have 82 and B should have 18.

## Read a trial

| File | Question answered |
| --- | --- |
| `trial.json` | Which replication policy, identities, WAL positions, fencing checks and promotion events did this run observe? |
| `ledger.jsonl` | What command and payload did the independent client submit on each attempt, and did it receive the full receipt? |
| `probes.jsonl` | Which fresh probe writes received a commit ACK and passed readback, and when? |
| `baseline.json` | What business state existed before transmission was interrupted? |
| `before-fence.json` | What could a reader observe on the old primary after transmission was interrupted? |
| `before-retry.json` | What business state survived on the promoted server, including receipts, balances, events and batch membership? |
| `after-retry.json` | What business state existed after replaying the original command identities and resolving unknown outcomes? |
| `service-after.json` | Did the separate application probe settle its own payment and update its accounts? |

The top-level `environment.json` records the host, Java, Python, Docker and Compose, plus SHA-256 digests of the application JAR and harness files. `manifest.json` identifies the expected trial directories, while `summary.json` collects results by trial. Keep the JSONL and history exports alongside the summary so another reader can recompute each result.

CI runs three repetitions per policy on Linux. The **PostgreSQL failover** workflow uploads the raw bundle as `failover-evidence-<commit>-<run attempt>`. Download the artifact for the commit you are comparing, extract it, and pass the directory containing `manifest.json` to `python failover/verify.py`. [Private failover workflow runs](https://github.com/NachoEstevo/settlement-lab/actions/workflows/failover.yml)

For example, inspect a trial's pre-retry history with the packaged `verify` command.

```sh
./bin/settlement-lab verify failover-evidence/async-01/before-retry.json
```

On Windows, substitute `.\bin\settlement-lab.bat` for the shell launcher. A history can be internally consistent even when it omits a whole transaction. The external ledger provides the missing reference set of ACKs.

## How time is measured

The host client uses one monotonic clock across process kills, routing changes and retries. It writes each request to the external ledger and calls `fsync` before sending it. Its reader records incoming ACKs and process failures in the same sequence. Clock values mark what the client could observe. PostgreSQL WAL positions establish what reached storage.

The probe scheduler starts an attempt every 500 ms, with additional attempts when the client changes routes. Each attempt writes a fresh UUID and a separate random payload into its own table. A fresh PostgreSQL TCP session reads it back by UUID and checks the payload. These `psql` clients run in the surviving container and connect over the project's network. Each has a two-second external deadline. A timeout or lost response during a write leaves the outcome unknown. A received commit ACK with a failed readback remains unverified. The timings include the Docker command and database connection overhead.

| Measurement | Start | End |
| --- | --- | --- |
| Gap between confirmed probe commits | Last old-primary probe ACK with matching readback | First promoted-primary probe ACK with matching readback |
| SQL recovery from primary failure | Controller dispatches Docker `SIGKILL` | The same first promoted-primary ACK |
| Application recovery from primary failure | Controller dispatches Docker `SIGKILL` | External client receives the first verified settlement ACK from the promoted-side JVM |
| PostgreSQL promotion command | Controller issues `pg_promote(true, 10)` | Controller receives successful completion |
| Readback of first recovered write | First promoted-primary probe ACK | Receipt of the matching payload through the independent connection |
| Unknown to observed resolution | Client records an unknown result for the first attempt | Receipt lookup for the interrupted cancellation, or the pre-retry survivor snapshot for an outstanding command |
| Unknown to retry ACK | Client records an unknown result for the first attempt | Client receives the identical retry's receipt |

The application has a separate check on each side of failover. It admits a one-unit payment in another settlement domain on the original primary and settles that same payment with a fresh command on the promoted primary. These JVMs connect over JDBC to the published loopback ports on the host. An independent JVM reads back the receipt for each command. The two accounts move from (2, 0) to (1, 1). The promoted-side result includes the cost of routing an application process to its new database. The SQL marker probes sample database write availability more frequently. Each probe uses identifiers separate from the main settlement case.

The two uncertainty durations are per command ID. For the interrupted cancellation, the second JVM's receipt lookup resolves the original outcome before the retry ACK arrives. For the other outstanding commands, the pre-retry survivor snapshot supplies the evidence about which receipts survived. A retry can then return the surviving receipt or execute a missing command. The async suffix writer is killed after promotion. Its recorded unknown interval starts when the client detects the closed channel, even though the original request had been outstanding throughout failover.

The client marks `fault_ns` when it requests the fence. The controller then inspects the still-running primary, issues Docker's kill command (`topology.fence.started_ns`) and checks that the old endpoint has closed. Recovery times start at this kill dispatch. The broader promotion call also checks the fence and inspects the promoted role. The first confirmed write on the new route depends on the route switch and probe cadence. PostgreSQL can become writable between attempts.

In sync mode the standby interruption starts before the primary kill. During this additional period the old primary stays online but cannot confirm the blocked business transaction. The measured lower bound runs from the end of the first query that observed its backend waiting in `SyncRep` to the beginning of the last query observing the same wait. The controller deliberately holds the fault for at least one second between samples.

## Measurement environment

The November 19, 2026 campaign ran the extracted v0.2.0 distribution on Windows 11 Pro build 26300, with an AMD Ryzen 7 9700X (8 cores, 16 logical processors), 61.7 GiB of host RAM, Oracle Java 21.0.7+8 and Python 3.13.1. Docker Desktop Engine 29.9.3 had 16 virtual CPUs and 30.2 GiB. Compose 5.7.0 started both PostgreSQL 18.7 containers for each trial. The environment file records the application JAR SHA-256 as `5874bffb0674f70a8b54bb1d25b37ecff6cad0e05ba8c6508b50faf8ad33c47a`.

## Limits

The two PostgreSQL containers, their volumes and the client share one physical host. This setup exercises process termination, an interrupted replication stream, promotion and client recovery. A host power cut or the loss of an availability zone would require independent hardware and storage domains. A promoted server operating alone can acknowledge locally durable commits while a replacement standby is being built. Replicated durability resumes only after a synchronous standby joins and the policy requires it again. [PostgreSQL 18 failover](https://www.postgresql.org/docs/18/warm-standby-failover.html)
