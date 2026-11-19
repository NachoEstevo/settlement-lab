package lab.settlement.settlement;

import lab.settlement.domain.Payment;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static lab.settlement.settlement.Ledger.Status.CANCELLED;
import static lab.settlement.settlement.Ledger.Status.QUEUED;
import static lab.settlement.settlement.Ledger.Status.SETTLED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerTest {
    private static final Map<String, Long> TWENTY_EACH = Map.of("A", 20L, "B", 20L, "C", 20L);
    private static final Payment AB = new Payment("ab", "A", "B", 100);
    private static final Payment BC = new Payment("bc", "B", "C", 100);
    private static final Payment CA = new Payment("ca", "C", "A", 100);
    private static final List<String> CYCLE = List.of("ab", "bc", "ca");

    @Test
    void simultaneousCycleSettlesEveryMemberAndSurvivesLateCancellation() {
        var ledger = ledgerWith(TWENTY_EACH, AB, BC, CA);

        for (String id : CYCLE) {
            assertRejected(ledger.settle("solo-" + id, List.of(id)));
        }
        var result = ledger.settle("cycle", CYCLE);

        assertTrue(result.committed(), result.detail());
        assertEquals(BigInteger.valueOf(300), result.grossSettled());
        assertEquals(Ledger.CancelResult.ALREADY_SETTLED, ledger.cancel("ca"));
        assertEquals(TWENTY_EACH, ledger.balances());
        assertEquals(Map.of("ab", new Ledger.Entry(AB, SETTLED, "cycle"),
                            "bc", new Ledger.Entry(BC, SETTLED, "cycle"),
                            "ca", new Ledger.Entry(CA, SETTLED, "cycle")), ledger.payments());
    }

    @Test
    void cancellingBeforeSettlementBreaksBothTheOriginalProposalAndTheRemainingChain() {
        var ledger = ledgerWith(TWENTY_EACH, AB, BC, CA);

        assertEquals(Ledger.CancelResult.CANCELLED, ledger.cancel("ca"));
        assertRejected(ledger.settle("original", CYCLE));
        assertRejected(ledger.settle("remaining", List.of("ab", "bc")));
        assertEquals(Ledger.CancelResult.ALREADY_CANCELLED, ledger.cancel("ca"));

        assertEquals(TWENTY_EACH, ledger.balances());
        assertEquals(Map.of("ab", new Ledger.Entry(AB, QUEUED, null),
                            "bc", new Ledger.Entry(BC, QUEUED, null),
                            "ca", new Ledger.Entry(CA, CANCELLED, null)), ledger.payments());
    }

    @Test
    void unequalCycleAtZeroCannotCommitEvenThoughItsGlobalNetIsZero() {
        var openings = Map.of("A", 0L, "B", 0L, "C", 0L);
        var ledger = ledgerWith(openings, AB, BC, new Payment("ca", "C", "A", 90));
        var queued = Map.copyOf(ledger.payments());

        assertRejected(ledger.settle("unequal", CYCLE));

        assertEquals(openings, ledger.balances());
        assertEquals(queued, ledger.payments());
    }

    @Test
    void grossOpeningTotalAndPerAccountFlowsCanEachExceedLongMax() {
        long max = Long.MAX_VALUE;
        var ledger = ledgerWith(Map.of("A", 0L, "B", max, "C", max),
                new Payment("ab", "A", "B", max), new Payment("ac", "A", "C", max),
                new Payment("ba", "B", "A", max), new Payment("ca", "C", "A", max),
                new Payment("extra", "B", "A", 1));

        var result = ledger.settle("large", List.of("ab", "ac", "ba", "ca", "extra"));

        assertTrue(result.committed(), result.detail());
        assertEquals(new BigInteger("36893488147419103229"), result.grossSettled()); // 4 * max + 1
        assertEquals(Map.of("A", 1L, "B", max - 1, "C", max), ledger.balances());
        assertEquals(5, ledger.payments().size());
        for (var entry : ledger.payments().values()) {
            assertEquals(SETTLED, entry.status());
            assertEquals("large", entry.batchId());
        }
    }

    @Test
    void overflowingCreditRejectsEvenAnEarlierFundedMember() {
        var openings = Map.of("A", 10L, "B", Long.MAX_VALUE, "C", 0L);
        var ledger = ledgerWith(openings, new Payment("ac", "A", "C", 3), new Payment("cb", "C", "B", 1));
        var queued = Map.copyOf(ledger.payments());

        assertRejected(ledger.settle("too-much-credit", List.of("ac", "cb")));

        assertEquals(openings, ledger.balances());
        assertEquals(queued, ledger.payments());
    }

    @Test
    void deficitCannotWrapBackIntoTheNonnegativeRange() {
        var openings = Map.of("A", 0L, "B", 0L, "C", 0L);
        var ledger = ledgerWith(openings, new Payment("ab", "A", "B", Long.MAX_VALUE),
                new Payment("ac", "A", "C", Long.MAX_VALUE));
        var queued = Map.copyOf(ledger.payments());

        assertRejected(ledger.settle("too-much-debit", List.of("ab", "ac")));

        assertEquals(openings, ledger.balances());
        assertEquals(queued, ledger.payments());
    }

    @Test
    void overflowingCreditsCannotWrapTheRecipientBalanceToZero() {
        var openings = Map.of("A", Long.MAX_VALUE, "B", 2L, "C", Long.MAX_VALUE);
        var ledger = ledgerWith(openings, new Payment("ab", "A", "B", Long.MAX_VALUE),
                new Payment("cb", "C", "B", Long.MAX_VALUE));
        var queued = Map.copyOf(ledger.payments());

        assertRejected(ledger.settle("wrapped-credit", List.of("ab", "cb")));

        assertEquals(openings, ledger.balances());
        assertEquals(queued, ledger.payments());
    }

    @Test
    void overlappingProposalsAndReusedBatchIdsPreserveTheOriginalSettlement() {
        var first = new Payment("first", "A", "B", 7);
        var next = new Payment("next", "A", "B", 1);
        var ledger = ledgerWith(Map.of("A", 10L, "B", 1L), first, next);

        assertTrue(ledger.settle("batch-one", List.of("first")).committed());
        assertRejected(ledger.settle("overlap", List.of("next", "first")));
        assertRejected(ledger.settle("batch-one", List.of("next")));

        assertEquals(Map.of("A", 3L, "B", 8L), ledger.balances());
        assertEquals(Map.of("first", new Ledger.Entry(first, SETTLED, "batch-one"),
                            "next", new Ledger.Entry(next, QUEUED, null)), ledger.payments());
    }

    @Test
    void duplicateAndUnknownMembersCannotSettleAnOtherwiseFundedPayment() {
        var payment = new Payment("p", "A", "B", 2);
        var openings = Map.of("A", 10L, "B", 0L);
        var ledger = ledgerWith(openings, payment);

        assertRejected(ledger.settle("duplicate", List.of("p", "p")));
        assertRejected(ledger.settle("unknown", List.of("p", "missing")));
        assertRejected(ledger.settle("empty", List.of()));

        assertEquals(openings, ledger.balances());
        assertEquals(Map.of("p", new Ledger.Entry(payment, QUEUED, null)), ledger.payments());
    }

    @Test
    void acceptsUpTo128DistinctFundedMembers() {
        var ledger = new Ledger(Map.of("A", 129L, "B", 0L));
        var ids = new ArrayList<String>();
        for (int i = 0; i < 129; i++) {
            String id = "p" + i;
            ids.add(id);
            ledger.submit(new Payment(id, "A", "B", 1));
        }

        assertRejected(ledger.settle("oversize", ids));
        var result = ledger.settle("full", ids.subList(0, 128));

        assertTrue(result.committed(), result.detail());
        assertEquals(BigInteger.valueOf(128), result.grossSettled());
        assertEquals(Map.of("A", 1L, "B", 128L), ledger.balances());
        assertEquals(QUEUED, ledger.payments().get("p128").status());
    }

    @Test
    void invalidTermsAndIdentityConflictsCannotReplaceAnAcceptedObligation() {
        var ledger = ledgerWith(TWENTY_EACH, AB);

        assertThrows(IllegalArgumentException.class, () -> ledger.submit(new Payment("ab", "B", "A", 1)));
        assertThrows(IllegalArgumentException.class, () -> ledger.submit(new Payment("bad", "A", "D", 1)));
        assertThrows(IllegalArgumentException.class, () -> ledger.submit(new Payment("zero", "A", "B", 0)));
        assertThrows(IllegalArgumentException.class, () -> ledger.submit(new Payment("negative", "A", "B", -1)));
        assertThrows(IllegalArgumentException.class, () -> ledger.submit(new Payment("self", "B", "B", 1)));
        assertThrows(IllegalArgumentException.class, () -> new Ledger(Map.of("A", -1L)));
        assertEquals(Ledger.CancelResult.NOT_FOUND, ledger.cancel("missing"));

        assertEquals(TWENTY_EACH, ledger.balances());
        assertEquals(Map.of("ab", new Ledger.Entry(AB, QUEUED, null)), ledger.payments());
    }

    private static Ledger ledgerWith(Map<String, Long> openings, Payment... payments) {
        var ledger = new Ledger(openings);
        for (Payment payment : payments) {
            ledger.submit(payment);
        }
        return ledger;
    }

    private static void assertRejected(Ledger.BatchResult result) {
        assertFalse(result.committed(), result.detail());
        assertEquals(BigInteger.ZERO, result.grossSettled());
    }
}
