package lab.settlement.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {
    @Test
    void defaultCycleShowsItsSettledMembersAndTheLateCancellation() {
        var result = invoke("demo", "cycle");
        assertEquals(0, result.exitCode());
        assertEquals("", result.err());
        assertTrue(result.out().contains("COMMITTED (gross settled=300)"));
        assertTrue(result.out().contains("Cancel CA: ALREADY_SETTLED batch=cycle"));
        assertTrue(result.out().contains("Closing balances (SIM): A=20 B=20 C=20 | total=60"));
        for (String member : new String[]{"AB", "BC", "CA"}) {
            assertTrue(result.out().contains(member + " SETTLED batch=cycle"));
        }
        assertEquals(result, invoke("demo", "cycle", "--race", "settle-first"));
    }

    @Test
    void cancelFirstReportsTheRejectedGroupAndTheUnfundedRemainder() {
        var result = invoke("demo", "cycle", "--race", "cancel-first");
        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("Cancel CA: CANCELLED"));
        assertTrue(result.out().contains("Batch cycle [AB, BC, CA]: REJECTED"));
        assertTrue(result.out().contains("Batch remainder [AB, BC]: REJECTED"));
        assertTrue(result.out().contains("CA CANCELLED"));
    }

    @Test
    void unequalCycleExplainsTheDeficit() {
        var result = invoke("demo", "unequal");
        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("Account A would close at -10"));
        assertTrue(result.out().contains("Closing balances (SIM): A=0 B=0 C=0 | total=0"));
    }

    @Test
    void helpNamesTheSupportedExamplesAndAnUnknownOrderIsAUsageError() {
        var help = invoke("--help");
        assertEquals(0, help.exitCode());
        assertTrue(help.out().contains("demo cycle --race cancel-first"));
        assertTrue(help.out().contains("demo unequal"));
        var invalid = invoke("demo", "cycle", "--race", "other");
        assertEquals(2, invalid.exitCode());
        assertEquals("", invalid.out());
        assertTrue(invalid.err().contains("--help"));
    }

    @Test
    void postgresCommandsValidateArgumentsBeforeIssuingAWrite() {
        var order = invoke("pg", "race", "other");
        assertEquals(2, order.exitCode());
        assertTrue(order.err().contains("cancel-first|settle-first"));
        var shortId = invoke("pg", "cancel", "1-1-1-1-1", "1-1-1-1-1", "1-1-1-1-1");
        assertEquals(2, shortId.exitCode());
        assertTrue(shortId.err().contains("standard UUID"));
        String id = "00000000-0000-0000-0000-000000000001";
        var nonCanonical = invoke("pg", "submit", id, id, id, id, id, "01");
        assertEquals(2, nonCanonical.exitCode());
        assertTrue(nonCanonical.err().contains("decimal integer"));
    }

    private record Run(int exitCode, String out, String err) {}

    @Test
    void selectionDemoReportsTheLiquiditySavingAndTheSuboptimalChoice() {
        var demo = invoke("demo", "selection");
        assertEquals(0, demo.exitCode());
        assertTrue(demo.out().contains("fifo-v1 queue_positions=[] gross=0 gap=300 (100%)"));
        assertTrue(demo.out().contains("heuristic-v1 queue_positions=[1, 2, 3] gross=300 gap=0 (0%)"));
        assertTrue(demo.out().contains("heuristic-v1 queue_positions=[1] gross=4 gap=1 (20%)"));
    }

    @Test
    void benchmarkDefaultsAreReproducibleAndInvalidCapacityStopsBeforeConnecting() {
        var defaults = invoke("benchmark", "economic");
        assertEquals(0, defaults.exitCode());
        assertTrue(defaults.out().contains("\"workload\":\"blocked-rings\""));
        assertEquals(defaults, invoke("benchmark", "economic", "20261119", "32"));
        assertEquals(2, invoke("benchmark", "economic", "42", "129").exitCode());
        assertEquals(2, invoke("pg", "capacity", "0", "12", "2", "42").exitCode());
    }

    private static Run invoke(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        try (var stdout = new PrintStream(out, true, StandardCharsets.UTF_8);
             var stderr = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            int code = Main.run(args, stdout, stderr);
            return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        }
    }
}
