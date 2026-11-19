package lab.settlement.benchmark;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.regex.Pattern;
import lab.settlement.postgres.Database;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapacityBenchmarkTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void workersDrainTheSameFundedFixtureAndMeasureEveryNonemptyAttempt(int workers) throws SQLException {
        var db = Database.fromEnvironment();
        var bytes = new ByteArrayOutputStream();
        try (var report = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            CapacityBenchmark.run(db, workers, 12, 2, 42, report);
        }
        String text = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("VERIFIED revision=18 settled=12 batches=6 gross=585 balance=9600"), text);
        var counts = Pattern.compile("attempts=(\\d+) committed=6 rejected=(\\d+) empty_polls=(\\d+)").matcher(text);
        assertTrue(counts.find(), text);
        int attempts = Integer.parseInt(counts.group(1));
        int rejects = Integer.parseInt(counts.group(2));
        assertEquals(6 + rejects, attempts);
        assertEquals(workers, Integer.parseInt(counts.group(3)));
        if (workers == 1) assertEquals(0, rejects);
        assertEquals(rejects, text.lines().filter(line -> line.startsWith("rejected_command=")).count());
        assertEquals(6, text.lines().filter(line -> line.startsWith("metric=")
                && line.contains("samples=" + attempts + " ")).count());
    }
}
