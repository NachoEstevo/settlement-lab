package lab.settlement.selection;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static lab.settlement.selection.SelectionPolicy.FIFO;
import static lab.settlement.selection.SelectionPolicy.HEURISTIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectionPolicyTest {
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), C = new UUID(0, 3);

    @Test
    void anUnfundedRingNeedsAllThreeVisiblePayments() {
        var balances = Map.of(A, 20L, B, 20L, C, 20L);
        var ring = snapshot(balances, pay(1, A, B, 100), pay(2, B, C, 100), pay(3, C, A, 100));
        assertTrue(FIFO.select(ring).members().isEmpty());
        assertEquals(ring.payments(), HEURISTIC.select(ring).members());
        assertReference("ring", ring, 0, 300, 300);

        var truncated = new QueueSnapshot(ring.revision(), 2, true, balances, ring.payments().subList(0, 2));
        assertTrue(HEURISTIC.select(truncated).members().isEmpty());
        assertReference("truncated-ring", truncated, 0, 0, 0);
    }

    @Test
    void theLatestDebitRemovalCanLoseOneFifthOfTheOptimum() {
        var earlier = pay(7, A, B, 4);
        var later = pay(19, A, C, 5);
        var queue = snapshot(Map.of(A, 5L, B, 0L, C, 0L), earlier, later);
        assertEquals(List.of(earlier), HEURISTIC.select(queue).members());
        assertEquals(List.of(later), SubsetOracle.optimum(queue).members());
        assertReference("four-versus-five", queue, 4, 4, 5);
    }

    @Test
    void interactingObligationsCanRequireThreeMembersEvenWithOnlyTwoAccounts() {
        var queue = snapshot(Map.of(A, 1L, B, 1L), pay(1, A, B, 10), pay(2, B, A, 4), pay(3, B, A, 6));
        for (int omit = 0; omit < 3; omit++) {
            var pair = new ArrayList<>(queue.payments());
            pair.remove(omit);
            assertFalse(SubsetOracle.feasible(queue.balances(), pair));
        }
        assertEquals(queue.payments(), HEURISTIC.select(queue).members());
        assertReference("interacting-obligations", queue, 0, 20, 20);
    }

    @Test
    void removalOfAnOverflowingCreditPreservesOtherTransfersBeyondTheBlockedFifoHead() {
        var older = pay(4, A, B, 3);
        var latestCredit = pay(7, C, B, 2);
        var laterTransfer = pay(9, C, A, 1);
        var queue = snapshot(Map.of(A, 10L, B, Long.MAX_VALUE - 3, C, 10L), older, latestCredit, laterTransfer);
        assertEquals(List.of(older), FIFO.select(queue).members());
        assertEquals(List.of(older, laterTransfer), HEURISTIC.select(queue).members());
        assertEquals(List.of(older, laterTransfer), SubsetOracle.optimum(queue).members());
    }

    @Test
    void deficitsTakePriorityOverSimultaneousOverflows() {
        var blocked = pay(1, B, A, 2);
        var payableAlone = pay(2, B, C, 1);
        var queue = snapshot(Map.of(A, Long.MAX_VALUE - 1, B, 1L, C, 0L), blocked, payableAlone);
        // B's latest debit is removed first. The older debit still overdraws B.
        assertTrue(HEURISTIC.select(queue).members().isEmpty());
        assertEquals(List.of(payableAlone), SubsetOracle.optimum(queue).members());
    }

    @Test
    void intermediatePositionsAndGrossCanExceedSignedLongInBothDirections() {
        long max = Long.MAX_VALUE;
        var queue = snapshot(Map.of(A, 0L, B, max, C, max), pay(1, A, B, max), pay(2, A, C, max),
                pay(3, B, A, max), pay(4, C, A, max));
        assertTrue(FIFO.select(queue).members().isEmpty());
        assertEquals(queue.payments(), HEURISTIC.select(queue).members());
        assertEquals(new BigInteger("36893488147419103228"), HEURISTIC.select(queue).gross());
        assertEquals(SubsetOracle.optimum(queue).gross(), HEURISTIC.select(queue).gross());
    }

    @Test
    void equalGrossIsRankedByCountThenByQueueSequence() {
        var earlier = pay(2, A, B, 2);
        var firstUnit = pay(8, A, B, 1);
        var secondUnit = pay(14, A, C, 1);
        var newestUnit = pay(27, A, C, 1);
        var queue = snapshot(Map.of(A, 2L, B, 0L, C, 0L), earlier, firstUnit, secondUnit, newestUnit);
        assertEquals(List.of(firstUnit, secondUnit), SubsetOracle.optimum(queue).members());
        assertEquals(List.of(earlier), HEURISTIC.select(queue).members());
    }

    @Test
    void selectorSupports128WhileTheExhaustiveOracleStopsAt12() {
        var queue = new ArrayList<QueuedPayment>();
        for (int seq = 1; seq <= 128; seq++) queue.add(pay(seq, A, B, 1));
        var balances = Map.of(A, 128L, B, 0L);
        var all = new QueueSnapshot(0, 128, true, balances, queue);
        assertEquals(queue, FIFO.select(all).members());
        assertEquals(queue, HEURISTIC.select(all).members());
        assertThrows(IllegalArgumentException.class, () -> new QueueSnapshot(0, 129, false, balances, queue));
        var twelve = new QueueSnapshot(0, 12, true, balances, queue.subList(0, 12));
        assertEquals(BigInteger.valueOf(12), SubsetOracle.optimum(twelve).gross());
        var thirteen = new QueueSnapshot(0, 13, true, balances, queue.subList(0, 13));
        assertThrows(IllegalArgumentException.class, () -> SubsetOracle.optimum(thirteen));
    }

    private static void assertReference(String fixture, QueueSnapshot queue, long fifo, long heuristic, long optimum) {
        BigInteger first = FIFO.select(queue).gross();
        BigInteger selected = HEURISTIC.select(queue).gross();
        BigInteger best = SubsetOracle.optimum(queue).gross();
        assertEquals(BigInteger.valueOf(fifo), first);
        assertEquals(BigInteger.valueOf(heuristic), selected);
        assertEquals(BigInteger.valueOf(optimum), best);
        BigInteger gap = best.subtract(selected);
        String percent = best.signum() == 0 ? "n/a" : new BigDecimal(gap).movePointRight(2)
                .divide(new BigDecimal(best), MathContext.DECIMAL64).stripTrailingZeros().toPlainString();
        System.out.printf("fixture=%s window=%s visible=%s fifo=%s heuristic=%s optimum=%s gap=%s gap_percent=%s%n",
                fixture, queue.window(), queue.payments().size(), first, selected, best, gap, percent);
    }

    private static QueuedPayment pay(int seq, UUID from, UUID to, long amount) {
        return new QueuedPayment(new UUID(1, seq), from, to, amount, seq);
    }

    private static QueueSnapshot snapshot(Map<UUID, Long> balances, QueuedPayment... payments) {
        return new QueueSnapshot(51, Math.max(1, payments.length), false, balances, List.of(payments));
    }
}
