# Run the Settlement Lab distribution

The `settlement-lab-0.1.0.zip` archive contains the Java application, runtime dependencies, database migrations, Linux and Windows launchers, and the Compose file for a local PostgreSQL 18 lab. Install Java 21 and extract the whole archive into a writable directory.

## Run the in-memory fixture

Open a terminal in the extracted `settlement-lab-0.1.0` directory. The fixture runs offline with a Java 21 runtime. If several Java installations are present, set `JAVA_HOME` to the Java 21 installation directory.

On Linux, use the shell launcher.

```sh
./bin/settlement-lab demo cycle
```

In Windows PowerShell, use the batch launcher.

```powershell
.\bin\settlement-lab.bat demo cycle
```

Three obligations of 100 settle together. Look for `gross settled=300`, closing balances of 20 each and a shared batch ID for payments AB, BC and CA. Pass `--race cancel-first` after `demo cycle` to see the order in which cancellation prevents the settlement.

## Compare queue policies offline

On Linux, run the following commands from the extracted directory. In Windows PowerShell use `.\bin\settlement-lab.bat` in place of `./bin/settlement-lab`.

```sh
./bin/settlement-lab demo selection
./bin/settlement-lab benchmark economic 20261119 32 > economic.json
```

The selection demo shows two small queues. In the three-payment ring, strict FIFO settles **0** and the multilateral heuristic settles **300**. In the second queue, A has 5 and owes 4 and 5. The heuristic keeps the earlier payment and settles **4**. Choosing the second would settle **5**, so the heuristic leaves a 20% gap to the optimum for that window.

The economic benchmark uses seed `20261119` and a window of `32` queued payments. The JSON report records eight logical ticks, every settlement opportunity and the payments left pending at the cutoff. Across all eight ticks, FIFO settles 0 gross in the blocked-rings workload and the heuristic settles 1,995. [Economic benchmark and workloads](https://github.com/NachoEstevo/settlement-lab/blob/v0.1.0/docs/economic-benchmark.md)

## Run the durable race

Start Docker with the Compose plugin. From the same extracted directory, run `docker compose up -d --wait`. The first start downloads the PostgreSQL image. The default database binds to `127.0.0.1:55432`.

```sh
./bin/settlement-lab pg race settle-first --drop-reply
```

On Windows use `.\bin\settlement-lab.bat pg race settle-first --drop-reply`. Each invocation creates a fresh synthetic domain. The CLI waits for PostgreSQL to observe the competing writer's lock wait, then recovers the winning receipt on a new connection. Look for `wait_event_type=Lock` and `reply_recovered=true`.

## Measure PostgreSQL capacity

With the Compose database running, execute the following command or its Windows batch-launcher equivalent.

```sh
./bin/settlement-lab pg capacity 4 128 4 42
```

The arguments select four workers, 128 payments, a queue window of four and seed 42. The verifier should report 32 batches, gross 6,317 and conserved cash of 102,400. Latency and throughput depend on disk flushes, worker scheduling, database lock contention, CPU and competing load. Compare local timings with repeated trials under the same setup. Run `docker compose down` when finished to stop the lab and retain its data volume. [Capacity benchmark and measurements](https://github.com/NachoEstevo/settlement-lab/blob/v0.1.0/docs/capacity-benchmark.md)

## Obtaining the ZIP

Download `settlement-lab-0.1.0.zip` from the release assets while signed in to a GitHub account with repository access. [Settlement Lab v0.1.0 release](https://github.com/NachoEstevo/settlement-lab/releases/tag/v0.1.0)

For a build of another commit, download the `settlement-lab-0.1.0` artifact of its successful **Package distribution** workflow. The GitHub artifact contains the application ZIP. Extract the GitHub download first and then the application ZIP.

To build the distribution from a checkout with a JDK 21 installation, run `./gradlew distZip` on Linux or `.\gradlew.bat distZip` in PowerShell. The application ZIP is written to `build/distributions/`.
