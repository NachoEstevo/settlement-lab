package lab.settlement.postgres;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PgAuthorityTest extends PgTestSupport {
    private final PgAuthority authority = new PgAuthority(DB);
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disconnectAtCommitCanBeRecoveredByANewClient(boolean commitCompleted) throws Exception {
        UUID domain = newDomain(Map.of(a, 10L, b, 0L));
        UUID payment = UUID.randomUUID(), command = UUID.randomUUID(), batch = UUID.randomUUID();
        authority.submit(domain, UUID.randomUUID(), payment, a, b, 3);
        var connectionFault = new PgAuthority.Hooks() {
            @Override
            public void beforeCommit(Connection connection) throws SQLException {
                if (!commitCompleted) {
                    disconnect(connection);
                }
            }

            @Override
            public void afterCommit(Connection connection) throws SQLException {
                if (commitCompleted) {
                    disconnect(connection);
                }
            }

            private void disconnect(Connection connection) throws SQLException {
                connection.close();
                throw new SQLException("Deliberate disconnect at the commit boundary", "08003");
            }
        };
        var disconnected = new PgAuthority(DB, connectionFault);
        var failure = assertThrows(SQLException.class,
                () -> disconnected.settle(domain, command, batch, 1, List.of(payment)));

        var recovered = new PgAuthority(Database.fromEnvironment());
        var original = recovered.receipt(domain, command);
        if (commitCompleted) {
            assertEquals(PgAuthority.UnknownCommitException.class, failure.getClass());
            assertHistory(domain, 2, 1, 2);
            assertBalances(domain, Map.of(a, 7L, b, 3L));
            assertPayments(domain, Map.of(payment, settled(batch)));
        } else {
            assertFalse(failure instanceof PgAuthority.UnknownCommitException);
            assertEquals(Optional.empty(), original);
            assertHistory(domain, 1, 0, 1);
            assertBalances(domain, Map.of(a, 10L, b, 0L));
            assertPayments(domain, Map.of(payment, QUEUED));
        }

        var result = recovered.settle(domain, command, batch, 1, List.of(payment));
        if (commitCompleted) {
            assertEquals(Optional.of(result), original);
        }
        assertEquals("COMMITTED", result.outcome());
        assertEquals(batch, result.batchId());
        assertEquals(2, result.revision());
        assertEquals(BigInteger.valueOf(3), result.gross());
        assertEquals(result, recovered.settle(domain, command, batch, 1, List.of(payment)));
        assertHistory(domain, 2, 1, 2);
        assertBalances(domain, Map.of(a, 7L, b, 3L));
        assertPayments(domain, Map.of(payment, settled(batch)));
    }

    @Test
    void commandIdentityAndBusinessIdentityProtectDifferentThings() throws SQLException {
        UUID domain = newDomain(Map.of(a, 2L, b, 0L));
        UUID creation = UUID.randomUUID(), payment = UUID.randomUUID(), earlyCancellation = UUID.randomUUID();
        var missing = authority.cancel(domain, earlyCancellation, payment);
        assertEquals("NOT_FOUND", missing.outcome());
        var queued = authority.submit(domain, creation, payment, a, b, 1);
        assertEquals("QUEUED", queued.outcome());
        assertEquals(queued, authority.submit(domain, creation, payment, a, b, 1));
        assertEquals("COMMAND_ID_CONFLICT", authority.submit(domain, creation, payment, a, b, 2).outcome());
        assertEquals("COMMAND_ID_CONFLICT", authority.cancel(domain, creation, payment).outcome());
        assertEquals("ALREADY_EXISTS", authority.submit(domain, UUID.randomUUID(), payment, a, b, 1).outcome());
        assertEquals("PAYMENT_ID_CONFLICT", authority.submit(domain, UUID.randomUUID(), payment, b, a, 2).outcome());
        assertEquals(missing, authority.cancel(domain, earlyCancellation, payment));
        assertPayments(domain, Map.of(payment, QUEUED));

        assertEquals("CANCELLED", authority.cancel(domain, UUID.randomUUID(), payment).outcome());
        assertEquals("ALREADY_CANCELLED", authority.cancel(domain, UUID.randomUUID(), payment).outcome());
        assertEquals(queued, authority.submit(domain, creation, payment, a, b, 1));
        assertPayments(domain, Map.of(payment, CANCELLED));
        assertHistory(domain, 2, 0, 6);
    }

    @Test
    void staleSnapshotCanSettleAfterAnUnrelatedAdmission() throws SQLException {
        UUID domain = newDomain(Map.of(a, 10L, b, 0L, c, 0L));
        UUID first = UUID.randomUUID(), unrelated = UUID.randomUUID(), batch = UUID.randomUUID();
        var observed = authority.submit(domain, UUID.randomUUID(), first, a, b, 2);
        authority.submit(domain, UUID.randomUUID(), unrelated, b, c, 5);

        var result = authority.settle(domain, UUID.randomUUID(), batch, observed.revision(), List.of(first));
        assertEquals("COMMITTED", result.outcome());
        assertEquals(3, result.revision());
        assertBalances(domain, Map.of(a, 8L, b, 2L, c, 0L));
        assertPayments(domain, Map.of(first, settled(batch), unrelated, QUEUED));
    }

    @Test
    void aRejectionMovesNoFundsAndOnlyACommittedBatchReservesItsId() throws SQLException {
        var openings = Map.of(a, 10L, b, 0L, c, Long.MAX_VALUE);
        UUID domain = newDomain(openings);
        UUID funded = UUID.randomUUID(), overflow = UUID.randomUUID(), unfunded = UUID.randomUUID();
        authority.submit(domain, UUID.randomUUID(), funded, a, b, 2);
        authority.submit(domain, UUID.randomUUID(), overflow, a, c, 1);
        authority.submit(domain, UUID.randomUUID(), unfunded, b, a, 5);
        UUID batch = UUID.randomUUID(), rejectedCommand = UUID.randomUUID();

        var tooMuchCredit = authority.settle(domain, rejectedCommand, batch, 3, List.of(funded, overflow));
        assertEquals("REJECTED", tooMuchCredit.outcome());
        assertEquals(BigInteger.ZERO, tooMuchCredit.gross());
        assertEquals("REJECTED", authority.settle(domain, UUID.randomUUID(), batch, 3, List.of(funded, unfunded)).outcome());
        assertEquals("REJECTED", authority.settle(domain, UUID.randomUUID(), batch, 3, List.of(funded, UUID.randomUUID())).outcome());
        assertThrows(IllegalArgumentException.class, () ->
                authority.settle(domain, UUID.randomUUID(), batch, 3, List.of(funded, funded)));
        assertBalances(domain, openings);
        assertPayments(domain, Map.of(funded, QUEUED, overflow, QUEUED, unfunded, QUEUED));
        assertHistory(domain, 3, 0, 6);

        assertEquals("COMMAND_ID_CONFLICT", authority.settle(domain, rejectedCommand, batch, 3, List.of(funded)).outcome());
        assertEquals("COMMITTED", authority.settle(domain, UUID.randomUUID(), batch, 3, List.of(funded)).outcome());
        assertEquals("BATCH_ID_CONFLICT", authority.settle(domain, UUID.randomUUID(), batch, 4, List.of(unfunded)).outcome());
        assertEquals(tooMuchCredit, authority.settle(domain, rejectedCommand, batch, 3, List.of(overflow, funded)));
        assertPayments(domain, Map.of(funded, settled(batch), overflow, QUEUED, unfunded, QUEUED));
        assertBalances(domain, Map.of(a, 8L, b, 2L, c, Long.MAX_VALUE));
        assertHistory(domain, 4, 1, 8);
    }

    @Test
    void settlementPersistsExactGrossAboveBigint() throws SQLException {
        var openings = Map.of(a, 0L, b, Long.MAX_VALUE, c, Long.MAX_VALUE);
        UUID domain = newDomain(openings);
        UUID ab = UUID.randomUUID(), ac = UUID.randomUUID(), ba = UUID.randomUUID(), ca = UUID.randomUUID();
        authority.submit(domain, UUID.randomUUID(), ab, a, b, Long.MAX_VALUE);
        authority.submit(domain, UUID.randomUUID(), ac, a, c, Long.MAX_VALUE);
        authority.submit(domain, UUID.randomUUID(), ba, b, a, Long.MAX_VALUE);
        authority.submit(domain, UUID.randomUUID(), ca, c, a, Long.MAX_VALUE);
        UUID command = UUID.randomUUID(), batch = UUID.randomUUID();
        var members = List.of(ab, ac, ba, ca);
        var result = authority.settle(domain, command, batch, 4, members);
        assertEquals("COMMITTED", result.outcome());
        assertEquals(new BigInteger("36893488147419103228"), result.gross());
        assertEquals(result, authority.settle(domain, command, batch, 4, members.reversed()));
        assertBalances(domain, openings);
        assertHistory(domain, 5, 1, 5);
    }
}
