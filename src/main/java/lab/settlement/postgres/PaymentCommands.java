package lab.settlement.postgres;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static lab.settlement.postgres.CommandSql.statement;
import static lab.settlement.postgres.CommandSql.updateOne;
import static lab.settlement.postgres.PgAuthority.Receipt;

/** Admission and cancellation on the connection that owns the domain lock. */
final class PaymentCommands {
    private PaymentCommands() {}

    static Receipt submit(Connection c, UUID domain, UUID payment, UUID from, UUID to, long amount, long revision)
            throws SQLException {
        try (var ps = statement(c, """
                SELECT from_account, to_account, amount_minor FROM payment WHERE domain_id = ? AND payment_id = ?
                """, domain, payment);
             var rows = ps.executeQuery()) {
            if (rows.next()) {
                boolean same = from.equals(rows.getObject("from_account", UUID.class))
                        && to.equals(rows.getObject("to_account", UUID.class)) && amount == rows.getLong("amount_minor");
                return same ? Receipt.at("ALREADY_EXISTS", "Payment " + payment + " already exists.", revision)
                        : Receipt.at("PAYMENT_ID_CONFLICT", "Payment " + payment + " has different terms.", revision);
            }
        }
        try (var ps = statement(c, "SELECT count(*) FROM account WHERE domain_id = ? AND account_id IN (?, ?)",
                domain, from, to);
             var rows = ps.executeQuery()) {
            rows.next();
            if (rows.getInt(1) != 2) {
                return Receipt.at("REJECTED", "Both payment accounts must exist in the domain.", revision);
            }
        }
        updateOne(c, """
                INSERT INTO payment (domain_id, payment_id, from_account, to_account, amount_minor, queue_seq, status)
                SELECT domain_id, ?, ?, ?, ?, next_queue_seq, 'QUEUED' FROM settlement_domain WHERE domain_id = ?
                """, payment, from, to, amount, domain);
        return Receipt.at("QUEUED", "Payment " + payment + " queued.", Math.incrementExact(revision));
    }

    static Receipt cancel(Connection c, UUID domain, UUID payment, long revision) throws SQLException {
        try (var ps = statement(c, "SELECT status, batch_id FROM payment WHERE domain_id = ? AND payment_id = ?",
                domain, payment);
             var rows = ps.executeQuery()) {
            if (!rows.next()) {
                return Receipt.at("NOT_FOUND", "Unknown payment " + payment + ".", revision);
            }
            switch (rows.getString("status")) {
                case "CANCELLED" -> {
                    return Receipt.at("ALREADY_CANCELLED", "Payment " + payment + " was cancelled.", revision);
                }
                case "SETTLED" -> {
                    return new Receipt("ALREADY_SETTLED", "Payment " + payment + " was settled.",
                            rows.getObject("batch_id", UUID.class), revision, BigInteger.ZERO);
                }
                case "QUEUED" -> { /* Eligible under the domain lock. */ }
                default -> throw new SQLException("Unexpected payment status.");
            }
        }
        updateOne(c, "UPDATE payment SET status = 'CANCELLED' WHERE domain_id = ? AND payment_id = ? AND status = 'QUEUED'",
                domain, payment);
        return Receipt.at("CANCELLED", "Payment " + payment + " cancelled.", Math.incrementExact(revision));
    }
}
