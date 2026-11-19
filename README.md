# Settlement Lab

Three banks each have 20 units of cash. A owes B 100, B owes C 100 and C owes A 100. No bank can make the first payment on its own. Settle all three together and each bank still has 20. What happens if C cancels while the batch is committing, or if the database acknowledges the settlement and then loses its primary?

Settlement Lab is a Java 21 and PostgreSQL 18 experiment that lets you run all three cases. The selector proposes a group of payments. The database locks the settlement domain, rechecks the proposal and commits balances, batch membership and a command receipt in one transaction. An offline verifier replays the resulting history. [Architecture and transaction diagrams](docs/architecture.md) · [Selection and locking decisions](docs/decisions.md)

## Select a batch

**From the release ZIP**, install a Java 21 runtime, download `settlement-lab-0.2.0.zip` and extract it. In the extracted directory containing `bin/`, run `.\bin\settlement-lab.bat demo selection` in PowerShell or `./bin/settlement-lab demo selection` on Linux. The queue example runs offline with the dependencies in the ZIP. [Download the v0.2.0 release](https://github.com/NachoEstevo/settlement-lab/releases/tag/v0.2.0)

**From a clone**, install a JDK 21 and run the whole block for your shell from the repository root. The Gradle Wrapper downloads its build tools and dependencies on first use. If you have several JDKs installed, set `JAVA_HOME` to the JDK 21 directory.

**Windows PowerShell**

```powershell
.\gradlew.bat -q installDist
Set-Location .\build\install\settlement-lab
.\bin\settlement-lab.bat demo selection
```

**Linux**

```sh
./gradlew -q installDist
cd build/install/settlement-lab
./bin/settlement-lab demo selection
```

| Queue | Strict FIFO | Multilateral heuristic | What the result tells you |
| --- | ---: | ---: | --- |
| Three payments of 100 in a ring | 0 | 300 | FIFO stops at the unfunded head. Settling the three payments together clears 300 gross with 60 cash. |
| A has 5 and queues payments of 4 and 5 | 4 | 4 | Both policies keep the older payment. Selecting the second alone would settle 5, so the heuristic leaves 20% of the possible volume unsettled. |

## Race settlement against cancellation in PostgreSQL

For the database experiments, start Docker with the Compose plugin and Linux containers. From the same installed directory, run `docker compose up -d --wait`. Compose downloads PostgreSQL on first use. The single-primary lab uses `127.0.0.1:55432` by default.

**Windows PowerShell**

```powershell
.\bin\settlement-lab.bat pg race cancel-first
.\bin\settlement-lab.bat pg race settle-first --drop-reply
```

**Linux**

```sh
./bin/settlement-lab pg race cancel-first
./bin/settlement-lab pg race settle-first --drop-reply
```

Each command seeds a fresh ring. Two database sessions race, and a third checks PostgreSQL's lock table before the first writer proceeds. Look for `wait_event_type=Lock` in both runs.

| First writer | Outcome |
| --- | --- |
| Cancellation | C's payment is cancelled. The batch is rejected, two payments remain queued and gross settled is 0. |
| Settlement | All three payments settle in one batch. Cancellation returns `ALREADY_SETTLED`. The winner's reply is withheld after commit, and retrying the same command on a new connection prints `reply_recovered=true`. |

Copy the printed domain and command IDs to inspect the receipt with `pg receipt DOMAIN COMMAND`. Use `pg export DOMAIN history.json` and then `verify history.json` with the same launcher to verify the balances and settled members offline. Run `docker compose down` to stop this lab and retain its history. [CLI commands, connection settings and retry semantics](docs/cli.md)

## Which receipts survive a primary failure?

The failover experiment uses a separate two-bank fixture. It starts its own disposable primary and physical standby on dynamically assigned loopback ports. Use Java 21, Docker Compose and Python 3 (tested with 3.12 and 3.13). From the same installed directory, run one trial per replication mode. Choose a fresh output directory each time.

**Windows PowerShell**

```powershell
python failover/run.py --mode both --repetitions 1 --out failover-evidence
python failover/verify.py failover-evidence
```

**Linux**

```sh
python3 failover/run.py --mode both --repetitions 1 --out failover-evidence
python3 failover/verify.py failover-evidence
```

The runner cuts replication, stops the old primary and promotes the standby. It saves the new primary's history before retrying any measured command. The offline verifier compares that snapshot with the receipts recorded by an external client. In **each of three published asynchronous trials**, one of six acknowledged commands was missing after promotion. It was a 7-unit settlement. In **each of three synchronous trials**, all five acknowledged commands survived. The 7-unit settlement waited for the disconnected standby and the client never received a commit acknowledgement for it from the old primary.

Use `--repetitions 3` to reproduce the published trial count. The measurements describe two database instances on one Docker host under a controlled replication cut. [Fault sequence, raw evidence and timing results](docs/failover.md)

## Benchmarks and tests

| Question | Command arguments for the installed launcher | What to inspect |
| --- | --- | --- |
| How much volume do the policies settle over time? | `benchmark economic 20261119 32` | Both policies see the same arrivals over eight ticks. Compare gross volume, pending payments and waiting times. [Economic workloads](docs/economic-benchmark.md) |
| What happens when four workers contend for one domain? | `pg capacity 4 128 4 42` | Start the single-primary lab first. The fixture settles 32 batches, moving 6,317 gross with 102,400 cash conserved. Lock waits and throughput depend on the host. [Capacity measurements](docs/capacity-benchmark.md) |

From the repository root, run `.\gradlew.bat test` in PowerShell or `./gradlew test` on Linux for the unit and property tests. On Linux, `bash scripts/test-postgres.sh` runs the database integration suite and verifies both race orders and recovery after a PostgreSQL restart in an isolated Compose project. [Integration script](scripts/test-postgres.sh) · [Packaged application guide](docs/distribution.md) · [Apache 2.0 license](LICENSE)
