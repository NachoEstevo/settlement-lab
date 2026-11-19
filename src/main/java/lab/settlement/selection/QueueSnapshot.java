package lab.settlement.selection;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** First queued payments and their participants' balances, all read at one revision. */
public record QueueSnapshot(long revision, int window, boolean hasMore, Map<UUID, Long> balances,
                            List<QueuedPayment> payments) {
    public static final int MAX_WINDOW = 128;

    public QueueSnapshot {
        balances = Map.copyOf(balances);
        payments = List.copyOf(payments);
        if (revision < 0 || window < 1 || window > MAX_WINDOW || payments.size() > window
                || (hasMore && payments.size() != window)) {
            throw new IllegalArgumentException("Invalid queue revision, window or continuation indicator.");
        }
        if (balances.values().stream().anyMatch(amount -> amount < 0)) {
            throw new IllegalArgumentException("Snapshot balances must be nonnegative.");
        }
        var ids = new HashSet<UUID>();
        long previous = 0;
        for (var payment : payments) {
            if (payment.queueSeq() <= previous || !ids.add(payment.id())) {
                throw new IllegalArgumentException("Snapshot payments need unique IDs and increasing queue sequence.");
            }
            if (!balances.containsKey(payment.from()) || !balances.containsKey(payment.to())) {
                throw new IllegalArgumentException("Missing snapshot balance for payment " + payment.id());
            }
            previous = payment.queueSeq();
        }
    }
}
