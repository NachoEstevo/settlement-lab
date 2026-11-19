package lab.settlement.postgres;

import lab.settlement.verification.HistoryExport;
import lab.settlement.verification.HistoryVerifier;
import org.junit.jupiter.api.BeforeAll;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class PgTestSupport {
    static final Database DB = Database.fromEnvironment();

    @BeforeAll
    static void migrate() throws SQLException {
        DB.migrate();
        try (var connection = DB.open(); var statement = connection.createStatement();
             var result = statement.executeQuery("SHOW server_version_num")) {
            assertTrue(result.next());
            assertEquals(18, result.getInt(1) / 10_000);
        }
    }

    static UUID newDomain(Map<UUID, Long> openings) throws SQLException {
        UUID domain = UUID.randomUUID();
        DB.createDomain(domain, openings);
        return domain;
    }

    static void assertBalances(UUID domain, Map<UUID, Long> expected) throws SQLException {
        try (var connection = DB.open(); var query = connection.prepareStatement(
                "SELECT account_id, balance_minor FROM account WHERE domain_id = ?")) {
            query.setObject(1, domain);
            try (var rows = query.executeQuery()) {
                var balances = new HashMap<UUID, Long>();
                while (rows.next()) {
                    balances.put(rows.getObject("account_id", UUID.class), rows.getLong("balance_minor"));
                }
                assertEquals(expected, balances);
            }
        }
    }

    record PaymentState(String status, UUID batch) {}

    static final PaymentState QUEUED = new PaymentState("QUEUED", null);
    static final PaymentState CANCELLED = new PaymentState("CANCELLED", null);

    static PaymentState settled(UUID batch) {
        return new PaymentState("SETTLED", batch);
    }

    static void assertPayments(UUID domain, Map<UUID, PaymentState> expected) throws SQLException {
        try (var connection = DB.open(); var query = connection.prepareStatement(
                "SELECT payment_id, status, batch_id FROM payment WHERE domain_id = ?")) {
            query.setObject(1, domain);
            try (var rows = query.executeQuery()) {
                var payments = new HashMap<UUID, PaymentState>();
                while (rows.next()) {
                    payments.put(rows.getObject("payment_id", UUID.class),
                            new PaymentState(rows.getString("status"), rows.getObject("batch_id", UUID.class)));
                }
                assertEquals(expected, payments);
            }
        }
    }

    static void assertHistory(UUID domain, long revision, int batches, int receipts) throws SQLException {
        String sql = """
                SELECT state_revision,
                    (SELECT count(*) FROM domain_event e WHERE e.domain_id = d.domain_id) AS events,
                    (SELECT count(*) FROM batch b WHERE b.domain_id = d.domain_id) AS batches,
                    (SELECT count(*) FROM command_receipt r WHERE r.domain_id = d.domain_id) AS receipts
                FROM settlement_domain d WHERE d.domain_id = ?
                """;
        try (var connection = DB.open(); var query = connection.prepareStatement(sql)) {
            query.setObject(1, domain);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(revision, rows.getLong("state_revision"), "domain revision");
                assertEquals(revision, rows.getLong("events"), "one event per revision");
                assertEquals(batches, rows.getInt("batches"), "committed batches");
                assertEquals(receipts, rows.getInt("receipts"), "stored command results");
            }
        }
        var replay = HistoryVerifier.verify(HistoryExport.read(DB, domain));
        assertEquals(revision, replay.revision());
        assertEquals(batches, replay.batches());
    }
}
