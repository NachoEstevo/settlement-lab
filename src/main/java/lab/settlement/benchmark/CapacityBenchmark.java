package lab.settlement.benchmark;

import java.io.PrintStream;
import java.math.BigInteger;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.ToLongFunction;
import lab.settlement.postgres.Database;
import lab.settlement.postgres.PgAuthority;
import lab.settlement.postgres.PgCoordinator;
import lab.settlement.selection.SelectionPolicy;
import lab.settlement.verification.HistoryExport;
import lab.settlement.verification.HistoryVerifier;

/** Wall-clock capacity of multiple JDBC coordinators draining one funded settlement domain. */
public final class CapacityBenchmark {
    private static final int ACCOUNTS = 8;

    private CapacityBenchmark() {}

    public static void run(Database db, int workers, int payments, int window, long seed, PrintStream out)
            throws SQLException {
        if (workers < 1 || workers > 32 || payments < 1 || payments > 8192 || window < 1 || window > 128) {
            throw new IllegalArgumentException("Capacity requires 1..32 workers, 1..8192 payments and a 1..128 window.");
        }
        db.migrate();
        var domain = UUID.randomUUID();
        long opening = 100L * payments;
        long workloadGross = seedDomain(db, domain, payments, opening, seed);
        out.printf("domain=%s seed=%s workers=%s payments=%s window=%s policy=%s%n",
                domain, seed, workers, payments, window, SelectionPolicy.HEURISTIC.version());
        printEnvironment(db, out);

        Trial trial = drain(db, domain, workers, payments, window);
        var history = HistoryExport.read(db, domain);
        var replay = HistoryVerifier.verify(history);
        long committed = trial.attempts.stream().filter(a -> a.receipt.outcome().equals("COMMITTED")).count();
        long expectedBatches = Math.ceilDiv(payments, window);
        if (replay.payments() != payments || replay.settled() != payments || replay.cancelled() != 0
                || replay.batches() != expectedBatches || committed != expectedBatches
                || replay.revision() != payments + committed
                || history.receipts().size() != payments + trial.attempts.size()
                || !replay.totalBalance().equals(BigInteger.valueOf(ACCOUNTS * opening))
                || !replay.grossSettled().equals(BigInteger.valueOf(workloadGross))) {
            throw new IllegalStateException("The verified domain disagrees with its funded workload or command counts.");
        }

        double seconds = trial.elapsed / 1_000_000_000.0;
        int attempts = trial.attempts.size();
        out.printf(Locale.ROOT, "elapsed_ms=%.3f attempts=%d committed=%d rejected=%d empty_polls=%d "
                        + "settled_payments_per_s=%.2f committed_batches_per_s=%.2f attempts_per_s=%.2f%n",
                seconds * 1000, attempts, committed, attempts - committed, workers,
                payments / seconds, committed / seconds, attempts / seconds);
        distribution(out, "selection", trial.attempts, Attempt::selection);
        distribution(out, "lock_statement", trial.attempts, Attempt::lock);
        distribution(out, "validation_and_writes", trial.attempts, Attempt::critical);
        distribution(out, "commit", trial.attempts, Attempt::commit);
        distribution(out, "transaction", trial.attempts, a -> a.lock + a.critical + a.commit);
        distribution(out, "cycle_response", trial.attempts, Attempt::response);
        for (var attempt : trial.attempts) {
            if (!"COMMITTED".equals(attempt.receipt.outcome())) {
                out.printf("rejected_command=%s snapshot=%s outcome=%s detail=%s%n", attempt.command,
                        attempt.revision, attempt.receipt.outcome(), attempt.receipt.detail());
            }
        }
        out.printf("VERIFIED revision=%s settled=%s batches=%s gross=%s balance=%s%n", replay.revision(),
                replay.settled(), replay.batches(), replay.grossSettled(), replay.totalBalance());
    }

    private static long seedDomain(Database db, UUID domain, int payments, long opening, long seed)
            throws SQLException {
        var balances = new HashMap<UUID, Long>();
        for (int i = 0; i < ACCOUNTS; i++) balances.put(new UUID(0, i), opening);
        db.createDomain(domain, balances);
        var authority = new PgAuthority(db);
        var random = new SplittableRandom(seed);
        long gross = 0;
        for (int i = 0; i < payments; i++) {
            int from = random.nextInt(ACCOUNTS);
            int to = (from + 1 + random.nextInt(ACCOUNTS - 1)) % ACCOUNTS;
            long amount = random.nextInt(1, 101);
            var receipt = authority.submit(domain, new UUID(1, i), new UUID(2, i),
                    new UUID(0, from), new UUID(0, to), amount);
            if (!receipt.outcome().equals("QUEUED")) throw new SQLException("Fixture admission failed: " + receipt);
            gross += amount;
        }
        return gross;
    }

    private record Attempt(UUID command, long revision, PgAuthority.Receipt receipt, long selection, long lock,
                           long critical, long commit, long response) {}
    private record Trial(long elapsed, List<Attempt> attempts) {}

    private static Trial drain(Database db, UUID domain, int workers, int payments, int window) throws SQLException {
        var ready = new CountDownLatch(workers);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(workers);
        var completion = new ExecutorCompletionService<List<Attempt>>(pool);
        try {
            for (int i = 0; i < workers; i++) {
                int worker = i;
                completion.submit(() -> {
                    ready.countDown();
                    start.await();
                    return runWorker(db, domain, worker, payments, window);
                });
            }
            if (!ready.await(30, TimeUnit.SECONDS)) throw new SQLException("Capacity workers did not reach the start gate.");
            long began = System.nanoTime();
            start.countDown();
            var attempts = new ArrayList<Attempt>();
            for (int i = 0; i < workers; i++) attempts.addAll(completion.take().get());
            return new Trial(System.nanoTime() - began, List.copyOf(attempts));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted during capacity trial.", interrupted);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof SQLException sql) throw sql;
            throw new SQLException("Capacity worker failed.", failure.getCause());
        } finally {
            pool.shutdownNow();
            start.countDown();
            try {
                if (!pool.awaitTermination(45, TimeUnit.SECONDS)) throw new SQLException("Capacity workers did not stop.");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted while stopping capacity workers.", interrupted);
            }
        }
    }

    private static List<Attempt> runWorker(Database db, UUID domain, int worker, int payments, int window)
            throws SQLException, InterruptedException {
        var timings = new CapacityTimings();
        var coordinator = new PgCoordinator(db, timings);
        var attempts = new ArrayList<Attempt>();
        // Every funded snapshot has a candidate. Each rejection must overlap another committed batch.
        for (int i = 0; i <= payments; i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Capacity trial cancelled.");
            long sequence = ((long) worker << 32) | i;
            UUID command = new UUID(3, sequence);
            long began = System.nanoTime();
            var cycle = coordinator.cycle(domain, SelectionPolicy.HEURISTIC, window, command, new UUID(4, sequence));
            long elapsed = System.nanoTime() - began;
            if (cycle.receipt().isEmpty()) {
                if (cycle.inspected() != 0) throw new SQLException("The funded queue had no candidate.");
                return attempts;
            }
            attempts.add(new Attempt(command, cycle.revision(), cycle.receipt().orElseThrow(), cycle.selectionNanos(),
                    timings.lock(), timings.critical(), timings.commit(), elapsed));
        }
        throw new SQLException("Capacity trial exceeded the number of possible advancing snapshots.");
    }

    private static void distribution(PrintStream out, String metric, List<Attempt> attempts,
                                     ToLongFunction<Attempt> extract) {
        long[] values = attempts.stream().mapToLong(extract).sorted().toArray();
        int p50 = Math.ceilDiv(values.length, 2) - 1;
        int p95 = Math.ceilDiv(values.length * 95, 100) - 1;
        out.printf(Locale.ROOT, "metric=%s samples=%d p50_ms=%.3f p95_ms=%.3f max_ms=%.3f%n",
                metric, values.length, values[p50] / 1e6, values[p95] / 1e6, values[values.length - 1] / 1e6);
    }

    private static void printEnvironment(Database db, PrintStream out) throws SQLException {
        out.printf("java=%s os=%s arch=%s jvm_processors=%s%n", Runtime.version(), System.getProperty("os.name"),
                System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors());
        try (var conn = db.open(); var q = conn.createStatement(); var rows = q.executeQuery("""
                SELECT current_setting('server_version'), current_setting('fsync'), current_setting('synchronous_commit')
                """)) {
            rows.next();
            out.printf("postgres=%s fsync=%s synchronous_commit=%s%n", rows.getString(1), rows.getString(2), rows.getString(3));
        }
    }
}
