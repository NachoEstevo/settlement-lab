package lab.settlement.benchmark;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lab.settlement.benchmark.EconomicBenchmark.Comparison;
import lab.settlement.benchmark.EconomicBenchmark.Outcome;
import lab.settlement.settlement.Ledger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EconomicBenchmarkTest {
    private static final long SEED = EconomicBenchmark.DEFAULT_SEED;

    @Test
    void everyPolicyGetsExactlyOneOpportunityPerTickAndNoFinalDrain() {
        for (var workload : EconomicBenchmark.run(SEED, 1).workloads()) {
            int arrivals = workload.schedule().stream().mapToInt(t -> t.arrivals().size()).sum();
            for (var run : workload.policies()) {
                assertEquals(8, run.decisions().size());
                for (int i = 0; i < 8; i++) {
                    assertEquals(workload.schedule().get(i).tick(), run.decisions().get(i).tick());
                    assertTrue(run.decisions().get(i).settled().size() <= 1);
                }
                assertEquals(run.decisions().stream().mapToInt(d -> d.settled().size()).sum(), run.settledCount());
                assertEquals(arrivals, run.settledCount() + run.cancelledCount() + run.pendingAtCutoff().size());
                assertEquals(workload.initialLiquidity(), run.participants().stream()
                        .map(p -> BigInteger.valueOf(p.closingBalance())).reduce(BigInteger.ZERO, BigInteger::add));
            }
        }
        var liquid = workload(1, "liquid");
        for (var run : liquid.policies()) {
            assertEquals(8, run.batchCount());
            assertEquals(48, run.pendingAtCutoff().size());
            assertEquals(7, run.oldestPendingAgeTicks());
            assertTrue(run.decisions().getFirst().hasMore());
            assertEquals(1, run.decisions().getFirst().inspected());
        }
    }

    @Test
    void ringReproducesTheAdmissionAndCancellationCalendarInBothPolicies() {
        var rings = workload(32, "blocked-rings");
        var fifo = policy(rings, "fifo-v1");
        var heuristic = policy(rings, "heuristic-v1");
        assertEquals(BigInteger.valueOf(60), rings.initialLiquidity());
        assertEquals(BigInteger.ZERO, fifo.grossSettled());
        assertEquals(BigInteger.valueOf(300), heuristic.decisions().getFirst().gross());
        assertEquals(BigInteger.valueOf(1995), heuristic.grossSettled());
        var request = rings.schedule().get(2).cancellations().getFirst();
        assertEquals(new EconomicBenchmark.Cancellation(request, Ledger.CancelResult.CANCELLED),
                fifo.decisions().get(2).cancellations().getFirst());
        assertEquals(new EconomicBenchmark.Cancellation(request, Ledger.CancelResult.ALREADY_SETTLED),
                heuristic.decisions().get(2).cancellations().getFirst());
        var narrow = policy(workload(2, "blocked-rings"), "heuristic-v1");
        assertTrue(narrow.decisions().getFirst().hasMore());
        assertEquals(BigInteger.ZERO, narrow.grossSettled());
    }

    @Test
    void asymmetricWorkloadExposesTheFirstWindowGapAndStarvedParticipantAtCutoff() {
        var work = workload(32, "asymmetric");
        var first = work.schedule().getFirst().arrivals();
        assertEquals(4, first.getFirst().amount());
        assertEquals(5, first.getLast().amount());
        var run = policy(work, "heuristic-v1");
        assertEquals(first.getFirst().id(), run.decisions().getFirst().settled().getFirst().paymentId());
        assertEquals(BigInteger.valueOf(4), run.decisions().getFirst().gross());
        assertEquals(BigInteger.valueOf(295), run.grossSettled());
        assertEquals(1, run.pendingAtCutoff().size());
        var unpaid = work.schedule().get(1).arrivals().getLast();
        assertEquals(new EconomicBenchmark.AgedPayment(unpaid.id(), 6), run.pendingAtCutoff().getFirst());
        var sender = run.participants().stream().filter(p -> p.accountId().equals(unpaid.from())).findFirst().orElseThrow();
        assertEquals(Map.of(6, 1), sender.pendingAgeHistogram());
        assertTrue(sender.settledWaitHistogram().isEmpty());
    }

    @Test
    void reportIsDeterministicAndDifferentSeedsChangeTheCalendar() throws IOException {
        var first = EconomicBenchmark.run(SEED, 32);
        var repeat = EconomicBenchmark.run(SEED, 32);
        assertEquals(first, repeat);
        assertArrayEquals(encode(first), encode(repeat));
        assertNotEquals(first.workloads().getFirst().schedule(),
                EconomicBenchmark.run(SEED + 1, 32).workloads().getFirst().schedule());
        assertThrows(IllegalArgumentException.class, () -> EconomicBenchmark.run(SEED, 0));
        assertThrows(IllegalArgumentException.class, () -> EconomicBenchmark.run(SEED, 129));
    }

    private static byte[] encode(EconomicBenchmark.Report result) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
            EconomicBenchmark.write(result, out);
        }
        return bytes.toByteArray();
    }

    private static Comparison workload(int window, String name) {
        return EconomicBenchmark.run(SEED, window).workloads().stream()
                .filter(w -> w.workload().equals(name)).findFirst().orElseThrow();
    }

    private static Outcome policy(Comparison workload, String version) {
        return workload.policies().stream().filter(p -> p.policy().equals(version)).findFirst().orElseThrow();
    }
}
