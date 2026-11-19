package lab.settlement.postgres;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import lab.settlement.selection.QueueSnapshot;
import lab.settlement.selection.QueuedPayment;

import static lab.settlement.postgres.CommandSql.statement;

/** Reads the bounded queue prefix and its participants' current balances together. */
final class PgQueue {
    private PgQueue() {}

    static QueueSnapshot read(Connection c, UUID domain, long revision, int window) throws SQLException {
        if (window < 1 || window > QueueSnapshot.MAX_WINDOW) {
            throw new IllegalArgumentException("Window must be between 1 and " + QueueSnapshot.MAX_WINDOW + ".");
        }
        try (var query = statement(c, """
                SELECT p.payment_id, p.from_account, p.to_account, p.amount_minor, p.queue_seq,
                       sender.balance_minor, recipient.balance_minor
                  FROM payment AS p
                  JOIN account AS sender ON sender.domain_id = p.domain_id AND sender.account_id = p.from_account
                  JOIN account AS recipient ON recipient.domain_id = p.domain_id AND recipient.account_id = p.to_account
                 WHERE p.domain_id = ? AND p.status = 'QUEUED'
                 ORDER BY p.queue_seq LIMIT ?
                """, domain, window + 1);
             var rows = query.executeQuery()) {
            var payments = new ArrayList<QueuedPayment>();
            var balances = new HashMap<UUID, Long>();
            while (payments.size() < window && rows.next()) {
                UUID from = rows.getObject(2, UUID.class);
                UUID to = rows.getObject(3, UUID.class);
                payments.add(new QueuedPayment(rows.getObject(1, UUID.class), from, to, rows.getLong(4), rows.getLong(5)));
                balances.put(from, rows.getLong(6));
                balances.put(to, rows.getLong(7));
            }
            boolean hasMore = payments.size() == window && rows.next();
            return new QueueSnapshot(revision, window, hasMore, balances, payments);
        }
    }
}
