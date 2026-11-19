package lab.settlement.postgres;

import java.math.BigInteger;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lab.settlement.selection.SelectionPolicy;
import org.junit.jupiter.api.Test;

import static lab.settlement.selection.SelectionPolicy.FIFO;
import static lab.settlement.selection.SelectionPolicy.HEURISTIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgSelectionTest extends PgTestSupport {
    private final PgAuthority authority = new PgAuthority(DB);
    private final PgCoordinator coordinator = new PgCoordinator(DB);
    private final UUID a = id(1), b = id(2), c = id(3);

    @Test
    void snapshotsOnlyQueuedPaymentsAndTheAccountsInsideTheWindow() throws SQLException {
        UUID domain = newDomain(Map.of(a, 9L, b, 2L, c, 3L));
        UUID cancelled = submit(domain, id(40), a, b, 1);
        authority.cancel(domain, UUID.randomUUID(), cancelled);
        UUID first = submit(domain, id(32), a, b, 1);
        UUID second = submit(domain, id(31), b, c, 2);
        var page = coordinator.snapshot(domain, 1);
        assertEquals(4, page.revision());
        assertEquals(List.of(first), page.payments().stream().map(p -> p.id()).toList());
        assertEquals(2, page.payments().getFirst().queueSeq());
        assertEquals(Map.of(a, 9L, b, 2L), page.balances());
        assertTrue(page.hasMore());
        var exact = coordinator.snapshot(domain, 2);
        assertFalse(exact.hasMore());
        assertEquals(List.of(first, second), exact.payments().stream().map(p -> p.id()).toList());
        assertEquals(exact.payments(), coordinator.snapshot(domain, 128).payments());
        assertThrows(IllegalArgumentException.class, () -> coordinator.snapshot(domain, 129));
    }

    @Test
    void staleFifoPrefixStillNetsToZeroButCanNoLongerStart() throws SQLException {
        UUID domain = newDomain(Map.of(a, 100L, b, 0L, c, 0L));
        UUID ab = submit(domain, id(90), a, b, 100);
        UUID ba = submit(domain, id(80), b, a, 100);
        var planned = coordinator.plan(domain, FIFO, 2);
        assertEquals(List.of(ab, ba), planned.selection().ids());
        UUID ac = submit(domain, id(70), a, c, 100);
        UUID intervening = UUID.randomUUID();
        assertEquals("COMMITTED", authority.settle(domain, UUID.randomUUID(), intervening, 3, List.of(ac)).outcome());

        UUID command = UUID.randomUUID(), batch = UUID.randomUUID();
        var declined = coordinator.commit(planned, command, batch).orElseThrow();
        assertEquals("REJECTED", declined.outcome());
        assertTrue(declined.detail().contains("intermediate balance"));
        assertEquals(declined, authority.settle(domain, command, batch, planned.revision(), FIFO, List.of(ba, ab)));
        assertEquals("COMMAND_ID_CONFLICT",
                authority.settle(domain, command, batch, planned.revision(), HEURISTIC, List.of(ab, ba)).outcome());
        assertBalances(domain, Map.of(a, 0L, b, 0L, c, 100L));
        assertPayments(domain, Map.of(ab, QUEUED, ba, QUEUED, ac, settled(intervening)));
        assertHistory(domain, 4, 1, 5);
    }

    @Test
    void fifoRevalidatesRecipientCapacityAtEveryIntermediateStep() throws SQLException {
        UUID domain = newDomain(Map.of(a, 1L, b, Long.MAX_VALUE, c, 0L));
        UUID ab = submit(domain, id(11), a, b, 1);
        UUID ba = submit(domain, id(12), b, a, 1);
        assertTrue(coordinator.plan(domain, FIFO, 2).selection().ids().isEmpty());
        var result = authority.settle(domain, UUID.randomUUID(), UUID.randomUUID(), 2, FIFO, List.of(ba, ab));
        assertEquals("REJECTED", result.outcome());
        assertBalances(domain, Map.of(a, 1L, b, Long.MAX_VALUE, c, 0L));
        var net = coordinator.cycle(domain, HEURISTIC, 2, UUID.randomUUID(), UUID.randomUUID());
        assertEquals("COMMITTED", net.receipt().orElseThrow().outcome());
        assertHistory(domain, 3, 1, 4);
    }

    @Test
    void staleCancellationAndAFeasibleNonprefixCannotCommitAsFifo() throws SQLException {
        UUID domain = newDomain(Map.of(a, 10L, b, 0L));
        UUID first = submit(domain, id(30), a, b, 2);
        UUID second = submit(domain, id(20), a, b, 2);
        UUID third = submit(domain, id(10), a, b, 2);
        var planned = coordinator.plan(domain, FIFO, 2);
        var nonprefix = authority.settle(domain, UUID.randomUUID(), UUID.randomUUID(), 3, FIFO, List.of(second));
        assertEquals("REJECTED", nonprefix.outcome());
        authority.cancel(domain, UUID.randomUUID(), first);
        var stale = coordinator.commit(planned, UUID.randomUUID(), UUID.randomUUID()).orElseThrow();
        assertEquals("REJECTED", stale.outcome());
        assertEquals(List.of(second, third), coordinator.plan(domain, FIFO, 2).selection().ids());
        assertBalances(domain, Map.of(a, 10L, b, 0L));
        assertHistory(domain, 4, 0, 6);

        for (UUID next : List.of(second, third)) {
            var cycle = coordinator.cycle(domain, FIFO, 1, UUID.randomUUID(), UUID.randomUUID());
            assertEquals(List.of(next), cycle.members());
            assertEquals("COMMITTED", cycle.receipt().orElseThrow().outcome());
        }
        assertHistory(domain, 6, 2, 8);
    }

    @Test
    void lateAdmissionKeepsFifoFeasibleUsingQueueSequenceInsteadOfCanonicalUuidOrder() throws SQLException {
        UUID domain = newDomain(Map.of(a, 100L, b, 0L));
        UUID first = submit(domain, id(90), a, b, 100);
        UUID second = submit(domain, id(80), b, a, 100);
        var plan = coordinator.plan(domain, FIFO, 2);
        UUID extra = submit(domain, id(70), a, b, 1);
        UUID command = UUID.randomUUID(), batch = UUID.randomUUID();
        var settled = coordinator.commit(plan, command, batch).orElseThrow();
        assertEquals("COMMITTED", settled.outcome());
        assertEquals(BigInteger.valueOf(200), settled.gross());
        assertEquals(settled, authority.settle(domain, command, batch, 2, FIFO, List.of(second, first)));
        assertEquals("COMMAND_ID_CONFLICT", authority.settle(domain, command, batch, 2, List.of(first, second)).outcome());
        assertPayments(domain, Map.of(first, settled(batch), second, settled(batch), extra, QUEUED));
        assertHistory(domain, 4, 1, 4);
    }

    @Test
    void emptyWindowResultSavesNoReceiptAndHeuristicRecordsItsVersion() throws SQLException {
        UUID domain = newDomain(Map.of(a, 20L, b, 20L, c, 20L));
        submit(domain, id(1), a, b, 100);
        submit(domain, id(2), b, c, 100);
        submit(domain, id(3), c, a, 100);
        UUID unused = UUID.randomUUID();
        var missed = coordinator.cycle(domain, HEURISTIC, 2, unused, UUID.randomUUID());
        assertTrue(missed.members().isEmpty());
        assertTrue(missed.receipt().isEmpty());
        assertTrue(missed.hasMore());
        assertEquals(2, missed.inspected());
        assertEquals(3, missed.revision());
        assertTrue(authority.receipt(domain, unused).isEmpty());
        UUID command = UUID.randomUUID(), batch = UUID.randomUUID();
        var cycle = coordinator.cycle(domain, HEURISTIC, 3, command, batch);
        assertFalse(cycle.hasMore());
        assertEquals(BigInteger.valueOf(300), cycle.receipt().orElseThrow().gross());
        try (var conn = DB.open(); var query = conn.prepareStatement(
                "SELECT policy_version FROM batch WHERE domain_id = ? AND batch_id = ?")) {
            query.setObject(1, domain);
            query.setObject(2, batch);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(SelectionPolicy.HEURISTIC.version(), rows.getString(1));
            }
        }
        assertHistory(domain, 4, 1, 4);
    }

    private UUID submit(UUID domain, UUID payment, UUID sender, UUID recipient, long amount) throws SQLException {
        assertEquals("QUEUED", authority.submit(domain, UUID.randomUUID(), payment, sender, recipient, amount).outcome());
        return payment;
    }

    private static UUID id(int suffix) {
        return new UUID(0, suffix);
    }
}
