package lab.settlement.postgres;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lab.settlement.selection.QueueSnapshot;
import lab.settlement.selection.Selection;
import lab.settlement.selection.SelectionPolicy;

import static lab.settlement.postgres.CommandSql.statement;

/** One bounded selection opportunity; the authority rechecks the proposal on its own connection. */
public final class PgCoordinator {
    private final Database db;
    private final PgAuthority authority;

    public PgCoordinator(Database db) {
        this.db = db;
        this.authority = new PgAuthority(db);
    }

    public PgCoordinator(Database db, PgAuthority.Hooks hooks) {
        this.db = db;
        this.authority = new PgAuthority(db, hooks);
    }

    public record Cycle(long revision, int inspected, boolean hasMore, List<UUID> members,
                        Optional<PgAuthority.Receipt> receipt, long selectionNanos) {
        public Cycle {
            members = List.copyOf(members);
        }
    }

    public record Plan(UUID domain, SelectionPolicy policy, int window, long revision, int inspected,
                       boolean hasMore, Selection selection, long selectionNanos) {}

    public QueueSnapshot snapshot(UUID domain, int window) throws SQLException {
        try (var c = db.open()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setReadOnly(true);
            c.setAutoCommit(false);
            long revision;
            try (var ps = statement(c, "SELECT state_revision FROM settlement_domain WHERE domain_id = ?", domain);
                 var rows = ps.executeQuery()) {
                if (!rows.next()) throw new SQLException("Unknown domain " + domain, "02000");
                revision = rows.getLong(1);
            }
            QueueSnapshot snapshot = PgQueue.read(c, domain, revision, window);
            c.commit();
            return snapshot;
        }
    }

    public Plan plan(UUID domain, SelectionPolicy policy, int window) throws SQLException {
        QueueSnapshot snapshot = snapshot(domain, window);
        long started = System.nanoTime();
        Selection selection = policy.select(snapshot);
        long selectionNanos = System.nanoTime() - started;
        return new Plan(domain, policy, window, snapshot.revision(), snapshot.payments().size(), snapshot.hasMore(),
                selection, selectionNanos);
    }

    public Optional<PgAuthority.Receipt> commit(Plan plan, UUID command, UUID batch) throws SQLException {
        List<UUID> ids = plan.selection().ids();
        if (ids.isEmpty()) return Optional.empty();
        return Optional.of(authority.settle(plan.domain(), command, batch, plan.revision(), plan.policy(), ids));
    }

    public Cycle cycle(UUID domain, SelectionPolicy policy, int window, UUID command, UUID batch) throws SQLException {
        Plan plan = plan(domain, policy, window);
        return new Cycle(plan.revision(), plan.inspected(), plan.hasMore(), plan.selection().ids(),
                commit(plan, command, batch), plan.selectionNanos());
    }
}
