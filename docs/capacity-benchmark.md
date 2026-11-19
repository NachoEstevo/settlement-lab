# PostgreSQL capacity benchmark

```sh
docker compose up -d --wait
./gradlew -q run --args="pg capacity 4 128 4 42"
```

The four arguments are the number of workers, payments, queue window and seed. The command accepts 1–32 workers, 1–8192 payments, a window of 1–128, and a signed 64-bit seed. Without arguments it uses 4 workers, 96 payments, a window of 8 and seed 42. Each invocation creates a fresh domain on the configured PostgreSQL primary and prints its UUID.

## What runs

Eight accounts each open with `100 × payments` units. Java's `SplittableRandom(seed)` chooses distinct endpoints and an integer amount from 1 through 100 per payment. The data fits the admission and balance rules even when all payments from one participant settle first. Stable payment IDs and admission order reproduce the input. The domain UUID isolates concurrent experiments.

Once all payments have been admitted, a gate releases the worker threads together. Each worker reads the next window on its own connection, runs `heuristic-v1` and attempts to commit on a separate connection. A competitor may settle the same members between those transactions. The losing attempt stores its rejection and the worker immediately reads a fresh snapshot. Each worker stops on its first empty window.

The elapsed clock runs from opening the start gate until all workers return, including their final empty reads. Fixture admission, thread pool startup, report formatting and verification are outside that interval. Rates use completed payments, committed batches or nonempty command attempts as their numerator. Each percentile uses one observation per nonempty attempt, including rejected attempts, with nearest-rank p50 and p95.

| Metric | Interval on the Java client |
| --- | --- |
| `selection` | Pure selector invocation after the snapshot has been read. |
| `lock_statement` | Immediately before and after the locking SELECT. Includes dispatch, the server's row-lock wait, execution and the JDBC round trip. |
| `validation_and_writes` | Acquired domain lock through validation, writes and receipt insertion, ending before COMMIT. |
| `commit` | JDBC COMMIT round trip. |
| `transaction` | From issuing the locking SELECT through the response to COMMIT, measured per attempt. |
| `cycle_response` | Whole coordination call, including snapshot reads, selection, physical connections and authority response. |

After the run, the independent verifier replays the domain. The benchmark checks gross, total cash, settled payment identities through replay, revision, number of batches, and receipt count against the workload and completed attempts. It prints every rejection with the command ID, observed revision and reason. Run `pg export DOMAIN FILE` to keep the complete history. The [economic benchmark](economic-benchmark.md) measures policies on a logical clock with a fixed number of settlement opportunities.

## Measured on November 19, 2026

The client ran on Windows 11 Pro build 26300 with an AMD Ryzen 7 9700X (8 cores, 16 logical processors), 61.7 GiB of host RAM and Oracle JDK 21.0.7+8. Docker Desktop Engine 29.9.3 ran the pinned PostgreSQL 18.7 Debian image with 16 virtual CPUs and 30.2 GiB available to the Linux engine. The database used its named volume, `fsync=on`, `full_page_writes=on` and `synchronous_commit=on`. Other development and build activity shared the host.

Each measured trial used a new Java process and a fresh domain with 128 payments, window 4 and seed 42. An initial 32-payment, 4-worker trial warmed the database. We ran the four worker counts in the orders (1, 2, 4, 8), (2, 4, 8, 1), (4, 8, 1, 2) and (8, 1, 2, 4). All 16 histories verified at revision 160 with 32 batches, 6,317 units settled gross and 102,400 units of conserved cash. The per-run data includes the domain IDs and both measured percentiles for every interval. [Trial data](capacity-samples.csv)

| Workers | Settled payments/s, median [range] | Attempts/s, median | Rejections per run | Cycle p50, ms | Cycle p95, ms |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 105.33 [70.69–124.37] | 26.33 | 0 | 36.54 | 44.33 |
| 2 | 68.80 [24.62–95.38] | 34.40 | 32 | 51.47 | 121.19 |
| 4 | 89.22 [62.23–104.56] | 89.22 | 96 | 42.85 | 55.72 |
| 8 | 50.08 [14.45–57.93] | 100.15 | 224 | 78.44 | 104.72 |

The latency columns in both tables take the median of four per-run percentiles. Rejections all referred to a member already settled by another worker.

| Workers | Selector p95, ms | Lock statement p95, ms | Validation + writes p95, ms | COMMIT p95, ms | Transaction p95, ms |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 0.14 | 1.62 | 14.43 | 3.97 | 19.68 |
| 2 | 0.24 | 25.32 | 35.69 | 9.62 | 60.75 |
| 4 | 0.14 | 19.93 | 15.40 | 3.63 | 28.65 |
| 8 | 0.15 | 66.85 | 15.06 | 4.95 | 76.96 |

Eight workers made 256 nonempty attempts to commit 32 funded batches. The median completion rate fell from 105 to 50 payments/s while the lock statement's p95 rose from 1.62 to 66.85 ms. Two workers also had one unusually slow trial with a 270 ms cycle p95. The four runs at each concurrency quantify this machine's variability. Each worker count saw the same payment terms and window.

## Regression check

For this machine, use a one-worker budget of **at least 70 settled payments/s** and a **cycle p95 of at most 90 ms**, taking the median across four fresh trials. The limits round outward from the observed one-worker extremes of 70.69 payments/s and 86.33 ms. Investigate when either limit is missed in two successive four-trial sets with the same configuration, including seed and window. Start by comparing lock, write and COMMIT timings and the competing activity on the machine. Changes in hardware, storage or JVM require new measurements. CI checks exact outcomes and sample counts on its own runners.
