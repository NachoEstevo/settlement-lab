package lab.settlement.selection;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public enum SelectionPolicy {
    FIFO("fifo-v1"),
    HEURISTIC("heuristic-v1");

    private static final BigInteger MAX_BALANCE = BigInteger.valueOf(Long.MAX_VALUE);
    private final String version;

    SelectionPolicy(String version) {
        this.version = version;
    }

    public String version() {
        return version;
    }

    public Selection select(QueueSnapshot snapshot) {
        Selection prefix = fifo(snapshot);
        if (this == FIFO || prefix.members().size() == snapshot.payments().size()) {
            return prefix;
        }
        Selection netted = removeInfeasiblePayments(snapshot);
        return compare(netted, prefix) > 0 ? netted : prefix;
    }

    private static Selection fifo(QueueSnapshot snapshot) {
        var balances = new HashMap<>(snapshot.balances());
        BigInteger gross = BigInteger.ZERO;
        int length = 0;
        for (var payment : snapshot.payments()) {
            long from = balances.get(payment.from());
            long to = balances.get(payment.to());
            if (from < payment.amount() || to > Long.MAX_VALUE - payment.amount()) {
                break;
            }
            balances.put(payment.from(), from - payment.amount());
            balances.put(payment.to(), to + payment.amount());
            gross = gross.add(BigInteger.valueOf(payment.amount()));
            length++;
        }
        return new Selection(snapshot.payments().subList(0, length), gross);
    }

    private static Selection removeInfeasiblePayments(QueueSnapshot snapshot) {
        var members = new ArrayList<>(snapshot.payments());
        var closing = new HashMap<UUID, BigInteger>();
        BigInteger gross = BigInteger.ZERO;
        for (var p : members) {
            var amount = BigInteger.valueOf(p.amount());
            closing.computeIfAbsent(p.from(), id -> BigInteger.valueOf(snapshot.balances().get(id)));
            closing.computeIfAbsent(p.to(), id -> BigInteger.valueOf(snapshot.balances().get(id)));
            closing.merge(p.from(), amount.negate(), BigInteger::add);
            closing.merge(p.to(), amount, BigInteger::add);
            gross = gross.add(amount);
        }
        // PostgreSQL orders UUIDs by their unsigned bytes, as does canonical UUID text.
        var accounts = closing.keySet().stream().sorted(Comparator.comparing(UUID::toString)).toList();
        while (true) {
            int remove = removalIndex(members, closing, accounts);
            if (remove < 0) {
                return new Selection(members, gross);
            }
            var payment = members.remove(remove);
            var amount = BigInteger.valueOf(payment.amount());
            closing.merge(payment.from(), amount, BigInteger::add);
            closing.merge(payment.to(), amount.negate(), BigInteger::add);
            gross = gross.subtract(amount);
        }
    }

    private static int removalIndex(List<QueuedPayment> members, Map<UUID, BigInteger> closing, List<UUID> accounts) {
        UUID overflow = null;
        for (UUID account : accounts) {
            var balance = closing.get(account);
            if (balance.signum() < 0) {
                return latestTransfer(members, account, true);
            }
            if (overflow == null && balance.compareTo(MAX_BALANCE) > 0) {
                overflow = account;
            }
        }
        return overflow == null ? -1 : latestTransfer(members, overflow, false);
    }

    private static int latestTransfer(List<QueuedPayment> members, UUID account, boolean outgoing) {
        for (int i = members.size() - 1; i >= 0; i--) {
            QueuedPayment payment = members.get(i);
            if (account.equals(outgoing ? payment.from() : payment.to())) {
                return i;
            }
        }
        throw new IllegalStateException("A violated balance has no transfer to remove.");
    }

    /** Positive when left has higher volume, more members, or an earlier sequence at the first tie. */
    private static int compare(Selection left, Selection right) {
        int order = left.gross().compareTo(right.gross());
        if (order != 0) return order;
        order = Integer.compare(left.members().size(), right.members().size());
        if (order != 0) return order;
        for (int i = 0; i < left.members().size(); i++) {
            order = Long.compare(right.members().get(i).queueSeq(), left.members().get(i).queueSeq());
            if (order != 0) return order;
        }
        return 0;
    }
}
