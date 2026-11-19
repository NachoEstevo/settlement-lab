package lab.settlement.demo;

import lab.settlement.postgres.Database;
import lab.settlement.postgres.PgAuthority;
import org.postgresql.PGConnection;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deterministic schedule for two independent JDBC writers to a fresh synthetic domain. */
public final class PgRace {
    public static final UUID A = id(1), B = id(2), C = id(3);
    public static final UUID AB = id(0xab), BC = id(0xbc), CA = id(0xca);
    public static final UUID BATCH = id(0x100);
    public static final UUID CANCEL_COMMAND = id(0x201), SETTLE_COMMAND = id(0x202);

    public record LockEvidence(int holderPid, int waiterPid, int observerPid,
                               String waitEventType, String waitEvent) {}
    public record Result(UUID domainId, long snapshotRevision, List<UUID> members, LockEvidence contention,
                         PgAuthority.Receipt cancellation, PgAuthority.Receipt settlement, boolean replyRecovered) {}

    private PgRace() {}

    public static Result run(Database db, boolean cancelFirst, boolean dropReply) throws SQLException {
        return run(db, prepare(db), cancelFirst, dropReply);
    }

    public static Proposal prepare(Database db) throws SQLException {
        UUID domain = UUID.randomUUID();
        db.createDomain(domain, Map.of(A, 20L, B, 20L, C, 20L));
        var authority = new PgAuthority(db);
        authority.submit(domain, id(0x301), AB, A, B, 100);
        authority.submit(domain, id(0x302), BC, B, C, 100);
        authority.submit(domain, id(0x303), CA, C, A, 100);
        Proposal proposal = readProposal(db, domain);
        if (!proposal.members().equals(List.of(AB, BC, CA))) {
            throw new SQLException("The cycle fixture did not produce the expected queue.");
        }
        return proposal;
    }

    public static Result run(Database db, Proposal proposal, boolean cancelFirst, boolean dropReply) throws SQLException {
        var holderPid = new CompletableFuture<Integer>();
        var waiterPid = new CompletableFuture<Integer>();
        var release = new CompletableFuture<Void>();
        var replyDropped = new AtomicBoolean();
        var holder = new PgAuthority(db, new PgAuthority.Hooks() {
            @Override public void afterLock(Connection conn) throws SQLException {
                holderPid.complete(backendPid(conn));
                await(release, "the probe to confirm the lock wait");
            }

            @Override public void afterCommit(Connection conn) throws SQLException {
                if (dropReply) {
                    replyDropped.set(true);
                    conn.close();
                    throw new SQLNonTransientConnectionException("Injected loss of the committed command reply.", "08006");
                }
            }
        });
        var waiter = new PgAuthority(db, new PgAuthority.Hooks() {
            @Override public void beforeLock(Connection conn) throws SQLException {
                waiterPid.complete(backendPid(conn));
            }

            @Override public void afterLock(Connection conn) throws SQLException {
                // A failed observation releases the holder by rollback. Abort the waiter in that case, too.
                await(release, "the probe to confirm the lock wait");
            }
        });

        // Open the observer before holding the mutex. Each poll runs in a fresh transaction.
        try (Connection observer = db.open()) {
            observer.setAutoCommit(true);
            var workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().name("pg-race-", 0).factory());
            try {
                var first = start(workers, holderPid, () -> proposal.execute(holder, cancelFirst));
                int holderBackend = await(holderPid, "the first writer to take the domain lock");
                var second = start(workers, waiterPid, () -> proposal.execute(waiter, !cancelFirst));
                int waiterBackend = await(waiterPid, "the second writer to reach the domain lock");
                LockEvidence evidence = observeContention(observer, holderBackend, waiterBackend, second);
                release.complete(null);
                PgAuthority.Receipt firstReceipt;
                boolean recovered = false;
                try {
                    firstReceipt = await(first, "the first writer's result");
                } catch (PgAuthority.UnknownCommitException uncertain) {
                    if (!replyDropped.get()) {
                        throw uncertain;
                    }
                    firstReceipt = proposal.recover(db, cancelFirst);
                    recovered = true;
                }
                PgAuthority.Receipt secondReceipt = await(second, "the second writer's result");
                return new Result(proposal.domain(), proposal.revision(), proposal.members(), evidence,
                        cancelFirst ? firstReceipt : secondReceipt, cancelFirst ? secondReceipt : firstReceipt,
                        recovered);
            } finally {
                release.completeExceptionally(new SQLException("Race ended before the lock wait was observed."));
                workers.shutdownNow();
                try {
                    if (!workers.awaitTermination(40, TimeUnit.SECONDS)) {
                        throw new SQLException("Timed out closing the race writers.");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Interrupted while closing the race writers.", e);
                }
            }
        }
    }

    public record Proposal(UUID domain, long revision, List<UUID> members) {
        public Proposal {
            members = List.copyOf(members);
        }

        private PgAuthority.Receipt execute(PgAuthority authority, boolean cancel) throws SQLException {
            if (cancel) {
                return authority.cancel(domain, CANCEL_COMMAND, CA);
            }
            return authority.settle(domain, SETTLE_COMMAND, BATCH, revision, members);
        }

        private PgAuthority.Receipt recover(Database db, boolean cancel) throws SQLException {
            UUID command = cancel ? CANCEL_COMMAND : SETTLE_COMMAND;
            var fresh = new PgAuthority(db);
            var stored = fresh.receipt(domain, command)
                    .orElseThrow(() -> new SQLException("The committed command receipt is missing."));
            var retried = execute(fresh, cancel);
            if (!retried.equals(stored)) {
                throw new SQLException("The replayed command returned a different receipt.");
            }
            return retried;
        }
    }

    private static Proposal readProposal(Database db, UUID domain) throws SQLException {
        try (Connection conn = db.open()) {
            conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            conn.setReadOnly(true);
            conn.setAutoCommit(false);
            long revision;
            try (var query = conn.prepareStatement("SELECT state_revision FROM settlement_domain WHERE domain_id = ?")) {
                query.setObject(1, domain);
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) {
                        throw new SQLException("The fixture domain is missing.");
                    }
                    revision = rows.getLong(1);
                }
            }
            var queued = new ArrayList<UUID>();
            try (var query = conn.prepareStatement("""
                    SELECT payment_id FROM payment
                     WHERE domain_id = ? AND status = 'QUEUED' ORDER BY queue_seq LIMIT 128
                    """)) {
                query.setObject(1, domain);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        queued.add(rows.getObject(1, UUID.class));
                    }
                }
            }
            conn.commit();
            return new Proposal(domain, revision, queued);
        }
    }

    private static LockEvidence observeContention(Connection observer, int holder, int waiter,
                                                 Future<?> waitingCommand) throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        try (var query = observer.prepareStatement("""
                SELECT pg_backend_pid(), a.wait_event_type, a.wait_event
                  FROM pg_stat_activity AS a
                 WHERE a.pid = ? AND a.wait_event_type = 'Lock'
                   AND ? = ANY(pg_blocking_pids(a.pid))
                """)) {
            query.setQueryTimeout(3);
            query.setInt(1, waiter);
            query.setInt(2, holder);
            do {
                try (var row = query.executeQuery()) {
                    if (row.next()) {
                        return new LockEvidence(holder, waiter, row.getInt(1), row.getString(2), row.getString(3));
                    }
                }
                if (waitingCommand.isDone()) {
                    await(waitingCommand, "the competing writer");
                    throw new SQLException("The competing command finished without waiting for the domain lock.");
                }
            } while (System.nanoTime() < deadline);
        }
        throw new SQLException("PostgreSQL did not report PID " + holder + " blocking PID " + waiter + " in time.");
    }

    private static <T> Future<T> start(ExecutorService workers, CompletableFuture<Integer> pid, Callable<T> operation) {
        return workers.submit(() -> {
            try {
                return operation.call();
            } catch (Exception failure) {
                pid.completeExceptionally(failure);
                throw failure;
            }
        });
    }

    private static <T> T await(Future<T> value, String operation) throws SQLException {
        try {
            return value.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof SQLException sql) {
                throw sql;
            }
            throw new SQLException("Failed while waiting for " + operation, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for " + operation, e);
        } catch (TimeoutException e) {
            throw new SQLException("Timed out waiting for " + operation, e);
        }
    }

    private static int backendPid(Connection conn) throws SQLException {
        // Reported by PostgreSQL during connection startup; no SQL precedes the authority's mutex.
        return conn.unwrap(PGConnection.class).getBackendPID();
    }

    private static UUID id(long suffix) {
        return new UUID(0, suffix);
    }
}
