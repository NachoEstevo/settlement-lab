package lab.settlement.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lab.settlement.postgres.Database;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgWorkerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void acknowledgementAndReplayMatchTheExportedReceiptAndConflictKeepsOriginal() throws Exception {
        var db = Database.fromEnvironment();
        db.migrate();
        UUID domain = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
        UUID payment = UUID.randomUUID(), command = UUID.randomUUID();
        String init = JSON.writeValueAsString(Map.of("op", "init", "domain", domain, "openings", Map.of(a, 7, b, 0)));
        String lookup = JSON.writeValueAsString(Map.of("op", "receipt", "domain", domain, "command", command));
        ObjectNode request = JSON.valueToTree(Map.of("op", "submit", "domain", domain, "command", command, "from", a,
                "to", b, "amount", 7, "payment", payment));
        String checkpoint = request.deepCopy().put("checkpoint", "beforeCommit").toString();
        var first = run(db, init, lookup, checkpoint);
        assertEquals("ready", first.get(0).get("event").asText());
        assertTrue(first.get(0).get("pid").isIntegralNumber());
        assertEquals("ok", first.get(1).get("event").asText());
        assertTrue(first.get(2).get("receipt").isNull());
        assertEquals("beforeCommit", first.get(3).get("event").asText());
        assertEquals("on", first.get(3).get("synchronousCommit").asText());
        assertTrue(first.get(3).get("backendPid").asInt() > 0);
        JsonNode ack = first.get(4);
        assertEquals("ack", ack.get("event").asText());
        assertEquals(command.toString(), ack.get("command").asText());

        String conflicting = JSON.writeValueAsString(Map.of("op", "cancel", "domain", domain,
                "command", command, "payment", payment));
        var second = run(db, request.toString(), conflicting, lookup,
                JSON.writeValueAsString(Map.of("op", "snapshot", "domain", domain)));
        assertEquals(ack, second.get(1));
        assertEquals("error", second.get(2).get("event").asText());
        var receipt = ack.get("receipt");
        assertEquals("0", receipt.get("gross").textValue());
        assertEquals("payment=" + payment + ";from=" + a + ";to=" + b + ";amount=7", receipt.get("payload").asText());
        assertEquals(receipt, second.get(3).get("receipt"));
        assertEquals(receipt, second.get(4).get("history").get("receipts").get(0));
        assertEquals(1, second.get(4).get("history").get("revision").asInt());
    }

    @Test
    void invalidFramesDoNotRunOrTerminateTheWorker() throws Exception {
        var db = new Database("jdbc:postgresql://127.0.0.1:1/unused", "unused", "unused");
        var frames = run(db, "", "null", "{}", "{\"op\":\"migrate\",\"op\":\"snapshot\"}",
                "{\"op\":\"migrate\",\"checkpoint\":\"afterCommit\"}", "{\"op\":\"init\"} {}");
        assertEquals(7, frames.size());
        for (var frame : frames.subList(1, frames.size())) {
            assertEquals("error", frame.get("event").asText());
            assertFalse(frame.has("sqlState"), "Malformed frames should fail before opening a connection");
        }
    }

    private static List<JsonNode> run(Database db, String... lines) throws Exception {
        byte[] requests = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
        var bytes = new ByteArrayOutputStream();
        try (var stream = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            new PgWorker(db, stream).serve(new ByteArrayInputStream(requests));
        }
        var frames = new ArrayList<JsonNode>();
        for (String line : bytes.toString(StandardCharsets.UTF_8).lines().toList()) {
            frames.add(JSON.readTree(line));
        }
        return frames;
    }
}
