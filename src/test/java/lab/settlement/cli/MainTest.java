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

    private record Run(int exitCode, String out, String err) {}

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
