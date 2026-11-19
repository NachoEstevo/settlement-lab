package lab.settlement.postgres;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lab.settlement.domain.BatchPositions;
import lab.settlement.domain.Payment;

import static lab.settlement.postgres.CommandSql.statement;
import static lab.settlement.postgres.CommandSql.updateOne;
import static lab.settlement.postgres.PgAuthority.Receipt;

/** Financial validation and writes for an explicitly proposed simultaneous batch. */
final class BatchCommand {
    private static final BigInteger MAX_BALANCE = BigInteger.valueOf(Long.MAX_VALUE);

    private BatchCommand() {}

    static Receipt settle(Connection c, UUID domain, UUID batch, long snapshot, List<UUID> members, long revision)
            throws SQLException {
        if (snapshot < 0 || snapshot > revision) {
            return Receipt.at("REJECTED", "Snapshot revision must be between 0 and " + revision + ".", revision);
        }
        try (var ps = statement(c, "SELECT 1 FROM batch WHERE domain_id = ? AND batch_id = ?", domain, batch);
             var rows = ps.executeQuery()) {
            if (rows.next()) {
                return new Receipt("BATCH_ID_CONFLICT", "Batch " + batch + " already committed.", batch, revision,
                        BigInteger.ZERO);
            }
        }

        var current = readMembers(c, domain, members);
        var eligible = new ArrayList<Payment>(members.size());
        var accounts = new HashSet<UUID>();
        for (UUID id : members) {
            Member member = current.get(id);
            if (member == null) {
                return Receipt.at("REJECTED", "Unknown payment " + id + ".", revision);
            }
            if (!member.status().equals("QUEUED")) {
                return Receipt.at("REJECTED", "Payment " + id + " is " + member.status() + ".", revision);
            }
            accounts.add(member.from());
            accounts.add(member.to());
            eligible.add(new Payment(id.toString(), member.from().toString(), member.to().toString(), member.amount()));
        }

        Map<String, Long> balances = readBalances(c, domain, accounts);
        BatchPositions positions = BatchPositions.calculate(balances, eligible);
        for (var entry : positions.closingBalances().entrySet()) {
            BigInteger balance = entry.getValue();
            if (balance.signum() < 0 || balance.compareTo(MAX_BALANCE) > 0) {
                return Receipt.at("REJECTED", "Account " + entry.getKey() + " would close at " + balance + ".", revision);
            }
        }
        BigInteger before = balances.values().stream().map(BigInteger::valueOf).reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger after = positions.closingBalances().values().stream().reduce(BigInteger.ZERO, BigInteger::add);
        if (!before.equals(after)) {
            throw new SQLException("Batch positions do not conserve the participants' balances.");
        }

        long nextRevision = Math.incrementExact(revision);
        updateOne(c, """
                INSERT INTO batch (domain_id, batch_id, snapshot_revision, commit_revision, policy_version)
                VALUES (?, ?, ?, ?, ?)
                """, domain, batch, snapshot, nextRevision, PgAuthority.POLICY);
        for (var entry : positions.closingBalances().entrySet()) {
            long balance = entry.getValue().longValueExact();
            if (balance != balances.get(entry.getKey())) {
                updateOne(c, "UPDATE account SET balance_minor = ? WHERE domain_id = ? AND account_id = ?",
                        balance, domain, UUID.fromString(entry.getKey()));
            }
        }
        try (var ps = statement(c, """
                UPDATE payment SET status = 'SETTLED', batch_id = ?
                WHERE domain_id = ? AND payment_id = ANY (?) AND status = 'QUEUED'
                """, batch, domain, members.toArray(UUID[]::new))) {
            if (ps.executeUpdate() != members.size()) {
                throw new SQLException("Batch membership changed under the domain lock.");
            }
        }
        return new Receipt("COMMITTED", "All " + members.size() + " members settled.", batch, nextRevision,
                positions.grossAmount());
    }

    private record Member(UUID from, UUID to, long amount, String status) {}

    private static Map<UUID, Member> readMembers(Connection c, UUID domain, List<UUID> members) throws SQLException {
        var result = new HashMap<UUID, Member>();
        try (var ps = statement(c, """
                SELECT payment_id, from_account, to_account, amount_minor, status
                FROM payment WHERE domain_id = ? AND payment_id = ANY (?)
                """, domain, members.toArray(UUID[]::new));
             var rows = ps.executeQuery()) {
            while (rows.next()) {
                result.put(rows.getObject("payment_id", UUID.class), new Member(rows.getObject("from_account", UUID.class),
                        rows.getObject("to_account", UUID.class), rows.getLong("amount_minor"), rows.getString("status")));
            }
        }
        return result;
    }

    private static Map<String, Long> readBalances(Connection c, UUID domain, Set<UUID> accounts) throws SQLException {
        var result = new HashMap<String, Long>();
        try (var ps = statement(c, """
                SELECT account_id, balance_minor FROM account WHERE domain_id = ? AND account_id = ANY (?)
                """, domain, accounts.toArray(UUID[]::new));
             var rows = ps.executeQuery()) {
            while (rows.next()) {
                result.put(rows.getString("account_id"), rows.getLong("balance_minor"));
            }
        }
        if (result.size() != accounts.size()) {
            throw new SQLException("A payment references an account outside the domain.");
        }
        return result;
    }
}
