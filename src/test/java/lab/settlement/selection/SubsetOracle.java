package lab.settlement.selection;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Exhaustive reference over a single observed window, independent of the production algorithms. */
final class SubsetOracle {
    private SubsetOracle() {}

    record Score(List<QueuedPayment> members, BigInteger gross) implements Comparable<Score> {
        static Score of(List<QueuedPayment> members) {
            BigInteger gross = BigInteger.ZERO;
            for (var p : members) {
                gross = gross.add(BigInteger.valueOf(p.amount()));
            }
            return new Score(List.copyOf(members), gross);
        }

        @Override
        public int compareTo(Score other) {
            int volume = gross.compareTo(other.gross);
            if (volume != 0) return volume;
            int count = Integer.compare(members.size(), other.members.size());
            if (count != 0) return count;
            for (int i = 0; i < members.size(); i++) {
                int age = Long.compare(other.members.get(i).queueSeq(), members.get(i).queueSeq());
                if (age != 0) return age;
            }
            return 0;
        }
    }

    static Score optimum(QueueSnapshot snapshot) {
        List<QueuedPayment> queue = snapshot.payments();
        if (queue.size() > 12) {
            throw new IllegalArgumentException("The exhaustive oracle supports at most 12 visible payments.");
        }
        Score best = Score.of(List.of());
        for (int mask = 1; mask < (1 << queue.size()); mask++) {
            var subset = new ArrayList<QueuedPayment>();
            for (int bit = 0; bit < queue.size(); bit++) {
                if ((mask & (1 << bit)) != 0) subset.add(queue.get(bit));
            }
            if (feasible(snapshot.balances(), subset)) {
                Score candidate = Score.of(subset);
                if (candidate.compareTo(best) > 0) best = candidate;
            }
        }
        return best;
    }

    static boolean feasible(Map<UUID, Long> balances, List<QueuedPayment> members) {
        for (var account : balances.entrySet()) {
            BigInteger incoming = BigInteger.ZERO;
            BigInteger outgoing = BigInteger.ZERO;
            for (var p : members) {
                if (p.from().equals(account.getKey())) outgoing = outgoing.add(BigInteger.valueOf(p.amount()));
                if (p.to().equals(account.getKey())) incoming = incoming.add(BigInteger.valueOf(p.amount()));
            }
            var closing = BigInteger.valueOf(account.getValue()).add(incoming).subtract(outgoing);
            if (closing.signum() < 0 || closing.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) return false;
        }
        return true;
    }

    static Score fifo(QueueSnapshot snapshot) {
        int length = 0;
        while (length < snapshot.payments().size()
                && feasible(snapshot.balances(), snapshot.payments().subList(0, length + 1))) {
            length++;
        }
        return Score.of(snapshot.payments().subList(0, length));
    }
}
