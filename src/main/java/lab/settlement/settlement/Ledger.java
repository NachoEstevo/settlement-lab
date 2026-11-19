package lab.settlement.settlement;

import lab.settlement.domain.BatchPositions;
import lab.settlement.domain.Payment;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Owns balances, payment states and batch membership for one single-threaded run. */
public final class Ledger {
    public enum Status { QUEUED, SETTLED, CANCELLED }
    public enum CancelResult { CANCELLED, ALREADY_CANCELLED, ALREADY_SETTLED, NOT_FOUND }
    public record Entry(Payment payment, Status status, String batchId) {}
    public record BatchResult(boolean committed, String detail, BigInteger grossSettled) {
        private static BatchResult rejected(String detail) {
            return new BatchResult(false, detail, BigInteger.ZERO);
        }
    }

    public static final int MAX_BATCH_SIZE = 128;
    private static final BigInteger MAX_BALANCE = BigInteger.valueOf(Long.MAX_VALUE);

    private final Map<String, Long> balances = new TreeMap<>();
    private final Map<String, Entry> payments = new LinkedHashMap<>();
    private final BigInteger openingTotal;

    public Ledger(Map<String, Long> openings) {
        if (openings == null || openings.isEmpty()) {
            throw new IllegalArgumentException("At least one opening account is required.");
        }
        openings.forEach((account, amount) -> {
            if (account == null || account.isBlank() || amount == null || amount < 0) {
                throw new IllegalArgumentException("Each opening needs an account ID and a nonnegative balance.");
            }
            balances.put(account, amount);
        });
        openingTotal = total(balances);
    }

    public Map<String, Long> balances() {
        return Collections.unmodifiableMap(new TreeMap<>(balances));
    }

    public Map<String, Entry> payments() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(payments));
    }

    public void submit(Payment payment) {
        if (payment == null || !balances.containsKey(payment.from()) || !balances.containsKey(payment.to())) {
            throw new IllegalArgumentException("Both payment accounts must exist.");
        }
        if (payments.containsKey(payment.id())) {
            throw new IllegalArgumentException("Payment " + payment.id() + " already exists.");
        }
        payments.put(payment.id(), new Entry(payment, Status.QUEUED, null));
    }

    public CancelResult cancel(String paymentId) {
        Entry entry = payments.get(paymentId);
        if (entry == null) {
            return CancelResult.NOT_FOUND;
        }
        return switch (entry.status()) {
            case CANCELLED -> CancelResult.ALREADY_CANCELLED;
            case SETTLED -> CancelResult.ALREADY_SETTLED;
            case QUEUED -> {
                payments.put(paymentId, new Entry(entry.payment(), Status.CANCELLED, null));
                yield CancelResult.CANCELLED;
            }
        };
    }

    public BatchResult settle(String batchId, List<String> paymentIds) {
        if (batchId == null || batchId.isBlank()) {
            return BatchResult.rejected("A batch needs an ID.");
        }
        if (paymentIds == null || paymentIds.isEmpty() || paymentIds.size() > MAX_BATCH_SIZE) {
            return BatchResult.rejected("A batch must contain between 1 and " + MAX_BATCH_SIZE + " payments.");
        }
        if (payments.values().stream().anyMatch(entry -> batchId.equals(entry.batchId()))) {
            return BatchResult.rejected("Batch " + batchId + " already committed.");
        }

        var members = new ArrayList<Payment>(paymentIds.size());
        var seen = new HashSet<String>();
        for (String id : paymentIds) {
            if (!seen.add(id)) {
                return BatchResult.rejected("Duplicate payment " + id + ".");
            }
            Entry entry = payments.get(id);
            if (entry == null) {
                return BatchResult.rejected("Unknown payment " + id + ".");
            }
            if (entry.status() != Status.QUEUED) {
                return BatchResult.rejected("Payment " + id + " is " + entry.status() + ".");
            }
            members.add(entry.payment());
        }

        BatchPositions positions = BatchPositions.calculate(balances, members);
        var nextBalances = new TreeMap<String, Long>();
        for (var position : positions.closingBalances().entrySet()) {
            BigInteger value = position.getValue();
            if (value.signum() < 0 || value.compareTo(MAX_BALANCE) > 0) {
                return BatchResult.rejected("Account " + position.getKey() + " would close at " + value
                        + " (allowed 0.." + MAX_BALANCE + ").");
            }
            nextBalances.put(position.getKey(), value.longValueExact());
        }
        if (!openingTotal.equals(total(nextBalances))) {
            throw new IllegalStateException("Closing balances do not conserve the opening total.");
        }

        balances.putAll(nextBalances);
        for (Payment member : members) {
            payments.put(member.id(), new Entry(member, Status.SETTLED, batchId));
        }
        return new BatchResult(true, "All members settled.", positions.grossAmount());
    }

    private static BigInteger total(Map<String, Long> balances) {
        return balances.values().stream().map(BigInteger::valueOf).reduce(BigInteger.ZERO, BigInteger::add);
    }
}
