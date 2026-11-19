package lab.settlement.postgres;

import lab.settlement.demo.PgRace;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PgContentionTest extends PgTestSupport {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationAndSettlementObserveTheWinnerAfterARealLockWait(boolean cancelFirst) throws SQLException {
        var race = PgRace.run(DB, cancelFirst, !cancelFirst);
        assertNotEquals(race.contention().holderPid(), race.contention().waiterPid());
        assertNotEquals(race.contention().observerPid(), race.contention().holderPid());
        assertNotEquals(race.contention().observerPid(), race.contention().waiterPid());
        assertEquals("Lock", race.contention().waitEventType());
        assertEquals(!cancelFirst, race.replyRecovered());
        assertEquals(3, race.snapshotRevision());
        assertEquals(List.of(PgRace.AB, PgRace.BC, PgRace.CA), race.members());
        assertBalances(race.domainId(), Map.of(PgRace.A, 20L, PgRace.B, 20L, PgRace.C, 20L));

        if (cancelFirst) {
            assertEquals("CANCELLED", race.cancellation().outcome());
            assertEquals("REJECTED", race.settlement().outcome());
            assertEquals(BigInteger.ZERO, race.settlement().gross());
            assertPayments(race.domainId(), Map.of(PgRace.AB, QUEUED, PgRace.BC, QUEUED, PgRace.CA, CANCELLED));
            assertHistory(race.domainId(), 4, 0, 5);
        } else {
            assertEquals("COMMITTED", race.settlement().outcome());
            assertEquals(BigInteger.valueOf(300), race.settlement().gross());
            assertEquals("ALREADY_SETTLED", race.cancellation().outcome());
            assertEquals(PgRace.BATCH, race.cancellation().batchId());
            var member = settled(PgRace.BATCH);
            assertPayments(race.domainId(), Map.of(PgRace.AB, member, PgRace.BC, member, PgRace.CA, member));
            assertHistory(race.domainId(), 4, 1, 5);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void simultaneousCommandsDeduplicateAnIdenticalBatchAndRejectAnOverlap(boolean sameCommand) throws Exception {
        UUID sender = UUID.randomUUID(), receiver = UUID.randomUUID();
        UUID domain = newDomain(Map.of(sender, 5L, receiver, 0L));
        var authority = new PgAuthority(DB);
        UUID first = UUID.randomUUID(), common = UUID.randomUUID(), last = UUID.randomUUID();
        for (UUID payment : List.of(first, common, last)) {
            assertEquals("QUEUED", authority.submit(domain, UUID.randomUUID(), payment, sender, receiver, 1).outcome());
        }

        var gate = new CyclicBarrier(2);
        var startTogether = new PgAuthority.Hooks() {
            @Override
            public void beforeLock(Connection connection) throws SQLException {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Interrupted before both commands arrived", e);
                } catch (BrokenBarrierException | TimeoutException e) {
                    throw new SQLException("Both connections must reach the same barrier", e);
                }
            }
        };
        UUID commandOne = UUID.randomUUID(), batchOne = UUID.randomUUID();
        UUID commandTwo = sameCommand ? commandOne : UUID.randomUUID();
        UUID batchTwo = sameCommand ? batchOne : UUID.randomUUID();
        var membersTwo = sameCommand ? List.of(common, first) : List.of(common, last);
        var workers = Executors.newFixedThreadPool(2);
        PgAuthority.Receipt one;
        PgAuthority.Receipt two;
        try {
            var left = workers.submit(() -> new PgAuthority(DB, startTogether)
                    .settle(domain, commandOne, batchOne, 3, List.of(first, common)));
            var right = workers.submit(() -> new PgAuthority(DB, startTogether)
                    .settle(domain, commandTwo, batchTwo, 3, membersTwo));
            one = left.get(20, TimeUnit.SECONDS);
            two = right.get(20, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }
        if (sameCommand) {
            assertEquals("COMMITTED", one.outcome());
            assertEquals(one, two, "Equivalent member orders must return the same receipt");
        } else {
            assertEquals(List.of("COMMITTED", "REJECTED"),
                    List.of(one.outcome(), two.outcome()).stream().sorted().toList());
        }

        boolean firstWon = one.outcome().equals("COMMITTED");
        var member = settled(firstWon ? batchOne : batchTwo);
        assertPayments(domain, Map.of(first, firstWon ? member : QUEUED, common, member, last, firstWon ? QUEUED : member));
        assertBalances(domain, Map.of(sender, 3L, receiver, 2L));
        assertHistory(domain, 4, 1, sameCommand ? 4 : 5);
    }
}
