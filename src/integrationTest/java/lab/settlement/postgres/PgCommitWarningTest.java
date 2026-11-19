package lab.settlement.postgres;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PgCommitWarningTest extends PgTestSupport {
    @Test
    void commitReturningWithAServerWarningIsUnknownEvenWhenTheReceiptCommittedLocally() throws SQLException {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), payment = UUID.randomUUID(), command = UUID.randomUUID();
        UUID domain = newDomain(Map.of(a, 5L, b, 0L));
        var authority = new PgAuthority(DB, new PgAuthority.Hooks() {
            @Override public void beforeCommit(Connection connection) throws SQLException {
                // Emit a real protocol warning during COMMIT, without changing cluster replication settings.
                try (var statement = connection.createStatement()) {
                    statement.execute("CREATE TEMP TABLE commit_test (id INTEGER)");
                    statement.execute("""
                            CREATE FUNCTION pg_temp.warn_at_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                            BEGIN RAISE WARNING 'commit warning test'; RETURN NULL; END $$
                            """);
                    statement.execute("""
                            CREATE CONSTRAINT TRIGGER warn_at_commit AFTER INSERT ON commit_test
                            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION pg_temp.warn_at_commit()
                            """);
                    statement.execute("INSERT INTO commit_test VALUES (1)");
                }
            }
        });
        var failure = assertThrows(PgAuthority.UnknownCommitException.class,
                () -> authority.submit(domain, command, payment, a, b, 5));
        assertEquals("08007", failure.getSQLState());
        var retrying = new PgAuthority(DB);
        var stored = retrying.receipt(domain, command).orElseThrow();
        assertEquals("QUEUED", stored.outcome());
        assertEquals(stored, retrying.submit(domain, command, payment, a, b, 5));
    }
}
