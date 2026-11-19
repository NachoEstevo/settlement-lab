package lab.settlement.postgres;

import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Connection settings and the atomic opening of a new settlement domain. */
public final class Database {
    private final PGSimpleDataSource source;

    public Database(String jdbcUrl, String user, String password) {
        source = new PGSimpleDataSource();
        source.setURL(jdbcUrl);
        source.setUser(user);
        source.setPassword(password);
        source.setConnectTimeout(5);
        source.setSocketTimeout(35);
        source.setOptions("-c lock_timeout=15000 -c statement_timeout=30000 -c synchronous_commit=on");
    }

    public static Database fromEnvironment() {
        var environment = System.getenv();
        return new Database(
                environment.getOrDefault("SETTLEMENT_JDBC_URL", "jdbc:postgresql://127.0.0.1:55432/settlement_lab"),
                environment.getOrDefault("SETTLEMENT_DB_USER", "settlement_lab"),
                environment.getOrDefault("SETTLEMENT_DB_PASSWORD", "settlement_lab"));
    }

    /** Every caller owns and closes an independent physical connection. */
    public Connection open() throws SQLException {
        return source.getConnection();
    }

    static void commitConfirmed(Connection connection) throws SQLException {
        connection.clearWarnings();
        connection.commit();
        // PostgreSQL can cancel a synchronous replication wait after the local commit.
        // pgJDBC reports that case as a connection warning, even though commit() returned.
        var warning = connection.getWarnings();
        if (warning != null) {
            throw new SQLException("COMMIT returned a warning; the requested durability is unknown.",
                    "08007", warning);
        }
    }

    public void migrate() {
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
    }

    public void createDomain(UUID domain, Map<UUID, Long> openings) throws SQLException {
        Objects.requireNonNull(domain, "domain");
        var accounts = Map.copyOf(openings);
        if (accounts.isEmpty() || accounts.values().stream().anyMatch(amount -> amount < 0)) {
            throw new IllegalArgumentException("A domain needs at least one account and nonnegative opening balances.");
        }
        try (var connection = open()) {
            connection.setAutoCommit(false);
            try (var insertDomain = connection.prepareStatement("INSERT INTO settlement_domain (domain_id) VALUES (?)");
                 var insertAccount = connection.prepareStatement("""
                         INSERT INTO account (domain_id, account_id, opening_minor, balance_minor)
                         VALUES (?, ?, ?, ?)
                         """)) {
                insertDomain.setObject(1, domain);
                insertDomain.executeUpdate();
                for (var entry : accounts.entrySet()) {
                    insertAccount.setObject(1, domain);
                    insertAccount.setObject(2, entry.getKey());
                    insertAccount.setLong(3, entry.getValue());
                    insertAccount.setLong(4, entry.getValue());
                    insertAccount.addBatch();
                }
                insertAccount.executeBatch();
            }
            commitConfirmed(connection);
        }
    }
}
