package lab.settlement.postgres;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

public final class PaymentLookup {
    public record PaymentState(UUID id, UUID from, UUID to, long amount, String status, UUID batchId) {}

    private PaymentLookup() {}

    public static Optional<PaymentState> find(Database db, UUID domainId, UUID paymentId) throws SQLException {
        try (var c = db.open(); var statement = c.prepareStatement("""
                SELECT from_account, to_account, amount_minor, status, batch_id
                  FROM payment WHERE domain_id = ? AND payment_id = ?
                """)) {
            statement.setObject(1, domainId);
            statement.setObject(2, paymentId);
            try (var row = statement.executeQuery()) {
                return row.next() ? Optional.of(new PaymentState(paymentId, row.getObject(1, UUID.class),
                        row.getObject(2, UUID.class), row.getLong(3), row.getString(4),
                        row.getObject(5, UUID.class))) : Optional.empty();
            }
        }
    }
}
