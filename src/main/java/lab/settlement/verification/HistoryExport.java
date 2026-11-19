package lab.settlement.verification;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lab.settlement.postgres.Database;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class HistoryExport {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);

    private HistoryExport() {}

    public static History read(Database db, UUID domain) throws SQLException {
        try (var tx = db.open()) {
            tx.setReadOnly(true);
            tx.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            tx.setAutoCommit(false);
            try (var statement = tx.prepareStatement("""
                    SELECT currency, scale, state_revision, next_queue_seq
                    FROM settlement_domain WHERE domain_id = ?
                    """)) {
                statement.setObject(1, domain);
                try (var row = statement.executeQuery()) {
                    if (!row.next()) {
                        throw new IllegalArgumentException("Unknown domain " + domain);
                    }
                    var history = new History(1, domain, row.getString(1), row.getInt(2), row.getLong(3), row.getLong(4),
                            rows(tx, domain, """
                                SELECT account_id, opening_minor, balance_minor
                                FROM account WHERE domain_id = ? ORDER BY account_id
                                """, r -> new History.Account(uuid(r, 1), r.getLong(2), r.getLong(3))),
                            rows(tx, domain, """
                                SELECT payment_id, from_account, to_account, amount_minor, queue_seq, status, batch_id
                                FROM payment WHERE domain_id = ? ORDER BY queue_seq
                                """, r -> new History.Payment(uuid(r, 1), uuid(r, 2), uuid(r, 3),
                                        r.getLong(4), r.getLong(5), r.getString(6), uuid(r, 7))),
                            rows(tx, domain, """
                                SELECT batch_id, snapshot_revision, commit_revision, policy_version
                                FROM batch WHERE domain_id = ? ORDER BY commit_revision
                                """, r -> new History.Batch(uuid(r, 1), r.getLong(2), r.getLong(3), r.getString(4))),
                            rows(tx, domain, """
                                SELECT command_id, kind, payload, outcome, detail, batch_id, state_revision, gross_minor
                                FROM command_receipt WHERE domain_id = ? ORDER BY command_id
                                """, r -> new History.Receipt(uuid(r, 1), r.getString(2), r.getString(3),
                                        r.getString(4), r.getString(5), uuid(r, 6), r.getLong(7),
                                        r.getBigDecimal(8).toBigIntegerExact())),
                            rows(tx, domain, """
                                SELECT state_revision, command_id, kind, payment_ids
                                FROM domain_event WHERE domain_id = ? ORDER BY state_revision
                                """, r -> new History.Event(r.getLong(1), uuid(r, 2), r.getString(3), members(r))));
                    tx.commit();
                    return history;
                }
            }
        }
    }

    private static List<UUID> members(ResultSet row) throws SQLException {
        var array = row.getArray(4);
        try {
            return List.of((UUID[]) array.getArray());
        } finally {
            array.free();
        }
    }

    private static UUID uuid(ResultSet row, int column) throws SQLException {
        return row.getObject(column, UUID.class);
    }

    @FunctionalInterface
    private interface RowReader<T> {
        T read(ResultSet row) throws SQLException;
    }

    private static <T> List<T> rows(Connection tx, UUID domain, String sql, RowReader<T> reader) throws SQLException {
        try (var statement = tx.prepareStatement(sql)) {
            statement.setObject(1, domain);
            try (var result = statement.executeQuery()) {
                var records = new ArrayList<T>();
                while (result.next()) {
                    records.add(reader.read(result));
                }
                return List.copyOf(records);
            }
        }
    }

    public static void write(Database db, UUID domain, Path path) throws SQLException, IOException {
        write(read(db, domain), path);
    }

    static void write(History history, Path path) throws IOException {
        Path target = path.toAbsolutePath();
        Path temporary = Files.createTempFile(target.getParent(), ".history-", ".tmp");
        try {
            var indent = new DefaultIndenter("  ", "\n");
            var format = new DefaultPrettyPrinter().withObjectIndenter(indent).withArrayIndenter(indent);
            JSON.writer(format).writeValue(temporary.toFile(), history);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static History read(Path path) throws IOException {
        return JSON.readValue(path.toFile(), History.class);
    }
}
