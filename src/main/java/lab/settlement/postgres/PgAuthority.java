package lab.settlement.postgres;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lab.settlement.settlement.Ledger;

import static lab.settlement.postgres.CommandSql.statement;
import static lab.settlement.postgres.CommandSql.updateOne;

/** The single write path for payment commands in an existing PostgreSQL domain. */
public final class PgAuthority {
    public static final String POLICY = "multilateral-v1";

    public record Receipt(String outcome, String detail, UUID batchId, long revision, BigInteger gross) {
        static Receipt at(String outcome, String detail, long revision) {
            return new Receipt(outcome, detail, null, revision, BigInteger.ZERO);
        }
    }

    /** Synchronization/fault points for the race harness. Ordinary clients use the no-hook constructor. */
    public interface Hooks {
        default void beforeLock(Connection connection) throws SQLException {}
        default void afterLock(Connection connection) throws SQLException {}
        default void beforeCommit(Connection connection) throws SQLException {}
        default void afterCommit(Connection connection) throws SQLException {}
    }

    public static final class UnknownCommitException extends SQLException {
        private static final long serialVersionUID = 1L;

        private UnknownCommitException(UUID command, Throwable cause) {
            super("Commit response unavailable. Retry command " + command + " with the same arguments.",
                    "08007", cause);
        }
    }

    private final Database db;
    private final Hooks hooks;

    public PgAuthority(Database db) {
        this(db, new Hooks() {});
    }

    public PgAuthority(Database db, Hooks hooks) {
        this.db = Objects.requireNonNull(db);
        this.hooks = Objects.requireNonNull(hooks);
    }

    public Receipt submit(UUID domain, UUID command, UUID payment, UUID from, UUID to, long amount)
            throws SQLException {
        Objects.requireNonNull(payment);
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        if (from.equals(to) || amount <= 0) {
            throw new IllegalArgumentException("Submission requires distinct accounts and a positive amount.");
        }
        String payload = "payment=" + payment + ";from=" + from + ";to=" + to + ";amount=" + amount;
        return execute(domain, command, "SUBMIT", payload, List.of(payment),
                (c, revision) -> PaymentCommands.submit(c, domain, payment, from, to, amount, revision));
    }

    public Receipt cancel(UUID domain, UUID command, UUID payment) throws SQLException {
        Objects.requireNonNull(payment);
        return execute(domain, command, "CANCEL", "payment=" + payment, List.of(payment),
                (c, revision) -> PaymentCommands.cancel(c, domain, payment, revision));
    }

    public Receipt settle(UUID domain, UUID command, UUID batch, long snapshotRevision, List<UUID> members)
            throws SQLException {
        Objects.requireNonNull(batch);
        var requested = new ArrayList<>(Objects.requireNonNull(members));
        if (requested.isEmpty() || requested.size() > Ledger.MAX_BATCH_SIZE || requested.contains(null)
                || new HashSet<>(requested).size() != requested.size()) {
            throw new IllegalArgumentException("A batch needs 1 to " + Ledger.MAX_BATCH_SIZE + " distinct payment IDs.");
        }
        List<UUID> sorted = requested.stream().sorted(Comparator.comparing(UUID::toString)).toList();
        String ids = sorted.stream().map(UUID::toString).collect(Collectors.joining(","));
        String payload = "batch=" + batch + ";snapshot=" + snapshotRevision + ";policy=" + POLICY + ";members=" + ids;
        return execute(domain, command, "SETTLE", payload, sorted,
                (c, revision) -> BatchCommand.settle(c, domain, batch, snapshotRevision, sorted, revision));
    }

    public Optional<Receipt> receipt(UUID domain, UUID command) throws SQLException {
        try (var c = db.open()) {
            return lookup(c, domain, command).map(Saved::receipt);
        }
    }

    @FunctionalInterface
    private interface Mutation {
        Receipt apply(Connection c, long revision) throws SQLException;
    }

    private Receipt execute(UUID domain, UUID command, String kind, String payload, List<UUID> members,
                            Mutation mutation) throws SQLException {
        Objects.requireNonNull(domain);
        Objects.requireNonNull(command);
        boolean commitStarted = false;
        try (var c = db.open()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try {
                hooks.beforeLock(c);
                lockDomain(c, domain);
                hooks.afterLock(c);
                // The lock may have waited. All validations run in fresh READ COMMITTED statements.
                var saved = lookup(c, domain, command);
                Receipt result;
                if (saved.isPresent()) {
                    Saved original = saved.get();
                    result = original.kind.equals(kind) && original.payload.equals(payload) ? original.receipt
                            : Receipt.at("COMMAND_ID_CONFLICT", "The command ID belongs to a different request.",
                                    original.receipt.revision());
                } else {
                    long revision = currentRevision(c, domain);
                    result = mutation.apply(c, revision);
                    if (result.revision() != revision) {
                        updateOne(c, """
                                UPDATE settlement_domain SET state_revision = ?, next_queue_seq = next_queue_seq + ?
                                WHERE domain_id = ? AND state_revision = ?
                                """, result.revision(), kind.equals("SUBMIT") ? 1 : 0, domain, revision);
                        updateOne(c, """
                                INSERT INTO domain_event (domain_id, state_revision, command_id, kind, payment_ids)
                                VALUES (?, ?, ?, ?, ?)
                                """, domain, result.revision(), command, kind, members.toArray(UUID[]::new));
                    }
                    updateOne(c, """
                            INSERT INTO command_receipt
                              (domain_id, command_id, kind, payload, outcome, detail, batch_id, state_revision, gross_minor)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """, domain, command, kind, payload, result.outcome(), result.detail(), result.batchId(),
                            result.revision(), new BigDecimal(result.gross()));
                }
                hooks.beforeCommit(c);
                commitStarted = true;
                c.commit();
                hooks.afterCommit(c);
                return result;
            } catch (SQLException | RuntimeException failure) {
                if (!commitStarted) {
                    try {
                        c.rollback();
                    } catch (SQLException rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                }
                throw failure;
            }
        } catch (SQLException | RuntimeException failure) {
            if (commitStarted) {
                throw new UnknownCommitException(command, failure);
            }
            throw failure;
        }
    }

    private static void lockDomain(Connection c, UUID domain) throws SQLException {
        try (var ps = statement(c, "SELECT domain_id FROM settlement_domain WHERE domain_id = ? FOR UPDATE", domain);
             var rows = ps.executeQuery()) {
            if (!rows.next()) {
                throw new SQLException("Unknown domain " + domain, "02000");
            }
        }
    }

    private static long currentRevision(Connection c, UUID domain) throws SQLException {
        try (var ps = statement(c, "SELECT state_revision FROM settlement_domain WHERE domain_id = ?", domain);
             var rows = ps.executeQuery()) {
            if (!rows.next()) {
                throw new SQLException("Locked domain disappeared.");
            }
            return rows.getLong(1);
        }
    }

    private record Saved(String kind, String payload, Receipt receipt) {}

    private static Optional<Saved> lookup(Connection c, UUID domain, UUID command) throws SQLException {
        try (var ps = statement(c, """
                SELECT kind, payload, outcome, detail, batch_id, state_revision, gross_minor
                FROM command_receipt WHERE domain_id = ? AND command_id = ?
                """, domain, command);
             ResultSet rows = ps.executeQuery()) {
            if (!rows.next()) {
                return Optional.empty();
            }
            var receipt = new Receipt(rows.getString("outcome"), rows.getString("detail"),
                    rows.getObject("batch_id", UUID.class), rows.getLong("state_revision"),
                    rows.getBigDecimal("gross_minor").toBigIntegerExact());
            return Optional.of(new Saved(rows.getString("kind"), rows.getString("payload"), receipt));
        }
    }
}
