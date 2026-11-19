package lab.settlement.verification;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyHistoryTest {
    private static final UUID A = id(1), B = id(2);
    // AB arrives first. The canonical command and event place BA first by UUID.
    private static final UUID AB = id(20), BA = id(10);
    private static final List<UUID> BOTH = List.of(BA, AB);
    private static final UUID BATCH = id(30), ADMIT_AB = id(40), ADMIT_BA = id(41), SETTLE = id(42);

    @Test
    void aFundedFifoPrefixReplaysInAdmissionOrderAndCannotBeRejected() {
        assertEquals(BigInteger.valueOf(200), HistoryVerifier.verify(cycle("fifo-v1", 100, 0, BOTH, true)).grossSettled());
        assertInvalid(cycle("fifo-v1", 100, 0, BOTH, false), "Receipt outcome disagrees with history");
    }

    @Test
    void netZeroCyclesWithInvalidIntermediateDebitsOrCreditsCanOnlyBeRejectedByFifo() {
        for (var openings : List.of(List.of(0L, 0L), List.of(100L, Long.MAX_VALUE))) {
            long a = openings.getFirst(), b = openings.getLast();
            assertEquals(0, HistoryVerifier.verify(cycle("fifo-v1", a, b, BOTH, false)).settled());
            assertInvalid(cycle("fifo-v1", a, b, BOTH, true), "FIFO payment " + AB);
            for (String policy : List.of("heuristic-v1", "multilateral-v1")) {
                assertEquals(2, HistoryVerifier.verify(cycle(policy, a, b, BOTH, true)).settled());
                assertInvalid(cycle(policy, a, b, BOTH, false), "Receipt outcome disagrees with history");
            }
        }
    }

    @Test
    void fifoCannotSkipAnEarlierQueuedMemberEvenWhenTheLaterPaymentIsFunded() {
        List<UUID> secondOnly = List.of(BA);
        assertEquals(0, HistoryVerifier.verify(cycle("fifo-v1", 100, 100, secondOnly, false)).settled());
        assertInvalid(cycle("fifo-v1", 100, 100, secondOnly, true), "FIFO proposal skips queued payment " + AB);
        assertEquals(1, HistoryVerifier.verify(cycle("heuristic-v1", 100, 100, secondOnly, true)).settled());
    }

    private static void assertInvalid(History history, String reason) {
        var invalid = assertThrows(IllegalArgumentException.class, () -> HistoryVerifier.verify(history));
        assertTrue(invalid.getMessage().contains(reason), invalid::getMessage);
    }

    private static History cycle(String policy, long a, long b, List<UUID> members, boolean committed) {
        boolean settledAB = committed && members.contains(AB);
        boolean settledBA = committed && members.contains(BA);
        long netToA = (settledBA ? 100L : 0L) - (settledAB ? 100L : 0L);
        var ab = new History.Payment(AB, A, B, 100, 1, settledAB ? "SETTLED" : "QUEUED", settledAB ? BATCH : null);
        var ba = new History.Payment(BA, B, A, 100, 2, settledBA ? "SETTLED" : "QUEUED", settledBA ? BATCH : null);
        int revision = committed ? 3 : 2;
        String ids = members.stream().map(UUID::toString).sorted().collect(Collectors.joining(","));
        var settlement = new History.Receipt(SETTLE, "SETTLE", "batch=" + BATCH + ";snapshot=2;policy=" + policy
                + ";members=" + ids, committed ? "COMMITTED" : "REJECTED", "Decision", committed ? BATCH : null,
                revision, BigInteger.valueOf(committed ? members.size() * 100L : 0));
        var first = new History.Event(1, ADMIT_AB, "SUBMIT", List.of(AB));
        var second = new History.Event(2, ADMIT_BA, "SUBMIT", List.of(BA));
        return new History(1, id(50), "SIM", 0, revision, 3,
                List.of(new History.Account(A, a, a + netToA), new History.Account(B, b, b - netToA)), List.of(ab, ba),
                committed ? List.of(new History.Batch(BATCH, 2, revision, policy)) : List.of(),
                List.of(admission(ADMIT_AB, ab), admission(ADMIT_BA, ba), settlement),
                committed ? List.of(first, second, new History.Event(revision, SETTLE, "SETTLE", members))
                        : List.of(first, second));
    }

    private static History.Receipt admission(UUID command, History.Payment payment) {
        return new History.Receipt(command, "SUBMIT", "payment=" + payment.id() + ";from=" + payment.from()
                + ";to=" + payment.to() + ";amount=100", "QUEUED", "Queued", null, payment.queueSeq(), BigInteger.ZERO);
    }

    private static UUID id(long suffix) {
        return new UUID(0, suffix);
    }
}
