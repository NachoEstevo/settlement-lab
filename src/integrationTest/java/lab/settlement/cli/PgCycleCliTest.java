package lab.settlement.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import lab.settlement.postgres.Database;
import lab.settlement.postgres.PgAuthority;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgCycleCliTest {
    @ParameterizedTest
    @ValueSource(strings = {"fifo", "heuristic"})
    void printedProposalReplaysTheOriginalDecisionAfterTheQueueAdvances(String policy) throws Exception {
        var db = Database.fromEnvironment();
        db.migrate();
        UUID domain = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
        db.createDomain(domain, Map.of(a, 5L, b, 0L));
        var authority = new PgAuthority(db);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        authority.submit(domain, UUID.randomUUID(), first, a, b, 5);
        authority.submit(domain, UUID.randomUUID(), second, a, b, 5);
        String command = UUID.randomUUID().toString(), batch = UUID.randomUUID().toString();
        String result = run("pg", "cycle", domain.toString(), policy, "1", command, batch);
        assertTrue(result.contains("has_more=true"));
        assertTrue(result.contains("members=" + first));
        String replay = result.lines().filter(s -> s.startsWith("replay=")).findFirst().orElseThrow().substring(7);
        String receipt = result.lines().filter(s -> s.startsWith("outcome=")).findFirst().orElseThrow();
        assertTrue(receipt.startsWith("outcome=COMMITTED revision=3 "));
        assertEquals(receipt, run(replay.split(" ")).strip());
        String empty = run("pg", "cycle", domain.toString(), policy, "1");
        assertTrue(empty.contains("snapshot_revision=3"));
        assertTrue(empty.contains("has_more=false"));
        assertTrue(empty.contains("NO_CANDIDATE"));
        assertTrue(run("pg", "snapshot", domain.toString()).contains("VERIFIED revision=3 payments=2 settled=1"));
    }

    private static String run(String... args) {
        var bytes = new ByteArrayOutputStream();
        try (var stream = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            int status = Main.run(args, stream, stream);
            String text = bytes.toString(StandardCharsets.UTF_8);
            assertEquals(0, status, text);
            return text;
        }
    }
}
