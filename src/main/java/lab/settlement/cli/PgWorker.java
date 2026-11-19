package lab.settlement.cli;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import lab.settlement.postgres.Database;
import lab.settlement.postgres.PgAuthority;
import lab.settlement.verification.History;
import lab.settlement.verification.HistoryExport;
import org.flywaydb.core.api.FlywayException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Sequential NDJSON endpoint. Each mutation uses the normal authority transaction. */
final class PgWorker {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Database db;
    private final PrintStream out;

    PgWorker(Database db, PrintStream out) {
        this.db = db;
        this.out = out;
    }

    void serve(InputStream input) throws IOException {
        send(Map.of("event", "ready", "pid", ProcessHandle.current().pid()));
        var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        for (String line; (line = reader.readLine()) != null;) {
            Object response;
            try {
                response = dispatch(JSON.readTree(line));
            } catch (SQLException e) {
                response = Map.of("event", "error", "message", e.toString(),
                        "sqlState", e.getSQLState() == null ? "" : e.getSQLState());
            } catch (JsonProcessingException | IllegalArgumentException | FlywayException e) {
                response = Map.of("event", "error", "message", e.toString());
            }
            send(response);
        }
    }

    private Object dispatch(JsonNode request) throws SQLException {
        if (request == null || !request.isObject()) {
            throw new IllegalArgumentException("Expected a JSON request object.");
        }
        String op = text(request, "op");
        var fields = switch (op) {
            case "migrate" -> Set.of("op");
            case "init" -> Set.of("op", "domain", "openings");
            case "snapshot" -> Set.of("op", "domain");
            case "receipt" -> Set.of("op", "domain", "command");
            case "submit" -> Set.of("op", "domain", "command", "payment", "from", "to", "amount", "checkpoint");
            case "cancel" -> Set.of("op", "domain", "command", "payment", "checkpoint");
            case "settle" -> Set.of("op", "domain", "command", "batch", "revision", "members", "checkpoint");
            default -> throw new IllegalArgumentException("Unknown worker op " + op);
        };
        request.fieldNames().forEachRemaining(field -> {
            if (!fields.contains(field)) {
                throw new IllegalArgumentException("Unexpected " + op + " field " + field);
            }
        });
        if (op.equals("migrate")) {
            db.migrate();
            return Map.of("event", "ok");
        }
        UUID domain = id(request, "domain");
        return switch (op) {
            case "init" -> {
                var openings = new HashMap<UUID, Long>();
                JsonNode accounts = request.required("openings");
                if (!accounts.isObject()) {
                    throw new IllegalArgumentException("Expected an openings object.");
                }
                accounts.properties().forEach(entry -> {
                    UUID account = PgCommands.id(entry.getKey());
                    if (openings.putIfAbsent(account, integer(entry.getValue())) != null) {
                        throw new IllegalArgumentException("Duplicate opening account " + account);
                    }
                });
                db.createDomain(domain, openings);
                yield Map.of("event", "ok");
            }
            case "snapshot" -> Map.of("event", "snapshot", "history", HistoryExport.read(db, domain));
            case "receipt" -> {
                UUID command = id(request, "command");
                var receipt = new PgAuthority(db).storedReceipt(domain, command);
                yield Map.of("event", "receipt", "receipt", receipt
                        .<Object>map(saved -> format(command, saved)).orElse(NullNode.getInstance()));
            }
            case "submit", "cancel", "settle" -> mutate(op, domain, request);
            default -> throw new IllegalArgumentException("Unknown worker op " + op);
        };
    }

    private Object mutate(String op, UUID domain, JsonNode request) throws SQLException {
        UUID command = id(request, "command");
        String checkpoint = request.has("checkpoint") ? text(request, "checkpoint") : "";
        if (!List.of("", "beforeCommit", "afterCommit").contains(checkpoint)) {
            throw new IllegalArgumentException("Unknown checkpoint " + checkpoint);
        }
        var authority = new PgAuthority(db, commitHooks(command, checkpoint));
        var result = switch (op) {
            case "submit" -> authority.submit(domain, command, id(request, "payment"), id(request, "from"),
                    id(request, "to"), integer(request.required("amount")));
            case "cancel" -> authority.cancel(domain, command, id(request, "payment"));
            case "settle" -> {
                JsonNode ids = request.required("members");
                if (!ids.isArray()) {
                    throw new IllegalArgumentException("Expected a members array.");
                }
                var members = new ArrayList<UUID>();
                ids.forEach(value -> members.add(PgCommands.id(value.asText())));
                yield authority.settle(domain, command, id(request, "batch"),
                        integer(request.required("revision")), members);
            }
            default -> throw new AssertionError(op);
        };

        // Read the immutable payload on a second connection. A failed read-back emits no ACK.
        var saved = authority.storedReceipt(domain, command)
                .orElseThrow(() -> new SQLException("Committed receipt unavailable on read-back.", "08007"));
        if (!result.equals(saved.receipt())) {
            throw new SQLException("Request differs from the command's original payload.", "22000");
        }
        return Map.of("event", "ack", "command", command, "receipt", format(command, saved));
    }

    private PgAuthority.Hooks commitHooks(UUID command, String checkpoint) {
        return new PgAuthority.Hooks() {
            @Override public void beforeCommit(Connection connection) throws SQLException {
                try (var statement = connection.createStatement()) {
                    // A server-side timeout can cancel SyncRep and finish COMMIT with a local-only warning.
                    // The driver's socket timeout and the external client's deadlines still bound this operation.
                    statement.execute("SET LOCAL statement_timeout = 0");
                    if (checkpoint.equals("beforeCommit")) {
                        try (var row = statement.executeQuery(
                                "SELECT pg_backend_pid(), current_setting('synchronous_commit')")) {
                            row.next();
                            send(Map.of("event", "beforeCommit", "command", command, "backendPid", row.getInt(1),
                                    "synchronousCommit", row.getString(2)));
                        }
                    }
                }
            }

            @Override public void afterCommit(Connection connection) throws SQLException {
                if (checkpoint.equals("afterCommit")) {
                    send(Map.of("event", "afterCommit", "command", command, "pid", ProcessHandle.current().pid()));
                    // The supervisor kills this JVM. An abandoned test eventually releases its connection.
                    try {
                        Thread.sleep(60_000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    throw new SQLException("Post-commit checkpoint expired without a client ACK.", "08007");
                }
            }
        };
    }

    private static History.Receipt format(UUID command, PgAuthority.StoredReceipt saved) {
        var result = saved.receipt();
        return new History.Receipt(command, saved.kind(), saved.payload(), result.outcome(), result.detail(),
                result.batchId(), result.revision(), result.gross());
    }

    private void send(Object value) {
        try {
            out.writeBytes(JSON.writeValueAsBytes(value));
            out.write('\n');
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize worker frame.", e);
        }
        if (out.checkError()) { // Also flushes so a supervisor can act on the checkpoint immediately.
            throw new UncheckedIOException(new IOException("Worker stdout disconnected."));
        }
    }

    private static UUID id(JsonNode request, String key) {
        return PgCommands.id(text(request, key));
    }

    private static String text(JsonNode request, String key) {
        JsonNode value = request.required(key);
        if (!value.isTextual()) {
            throw new IllegalArgumentException("Expected text for " + key);
        }
        return value.textValue();
    }

    private static long integer(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new IllegalArgumentException("Expected a nonnegative 64-bit integer.");
        }
        return value.longValue();
    }
}
