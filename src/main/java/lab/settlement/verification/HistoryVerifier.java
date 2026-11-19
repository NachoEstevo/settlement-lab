package lab.settlement.verification;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Replays the exported history without calling the authority or its position calculator. */
public final class HistoryVerifier {
    public record Summary(long revision, int payments, int settled, int cancelled, int batches,
                          BigInteger grossSettled, BigInteger totalBalance) {}

    private static final BigInteger MAX_BALANCE = BigInteger.valueOf(Long.MAX_VALUE);
    private final History history;
    private final Map<UUID, History.Payment> payments;
    private final Map<UUID, History.Batch> batches;
    private final Map<UUID, History.Receipt> receipts;
    private final Map<Long, List<History.Receipt>> decisions = new HashMap<>();
    private final Map<UUID, BigInteger> balances = new HashMap<>();
    private final Map<UUID, String> states = new HashMap<>();
    private final Set<UUID> queue = new LinkedHashSet<>();
    private final Map<UUID, UUID> memberships = new HashMap<>();
    private final Set<UUID> committedBatches = new HashSet<>();
    private final Set<UUID> mutations = new HashSet<>();
    private BigInteger gross = BigInteger.ZERO;
    private long revision;

    public static Summary verify(Path path) throws IOException {
        return verify(HistoryExport.read(path));
    }

    public static Summary verify(History history) {
        return new HistoryVerifier(history).replay();
    }

    private HistoryVerifier(History history) {
        check(history != null && history.version() == 1, "Unsupported history format");
        check(history.domainId() != null && "SIM".equals(history.currency()) && history.scale() == 0,
                "Invalid domain identity or currency");
        this.history = history;
        payments = index(history.payments(), History.Payment::id, "payment");
        batches = index(history.batches(), History.Batch::id, "batch");
        receipts = index(history.receipts(), History.Receipt::commandId, "receipt");
        check(history.accounts() != null && !history.accounts().isEmpty(), "Missing opening accounts");
        for (var account : history.accounts()) {
            check(account != null && account.id() != null && account.opening() >= 0, "Invalid opening account");
            check(balances.put(account.id(), BigInteger.valueOf(account.opening())) == null,
                    "Duplicate opening account " + account.id());
        }
        for (var receipt : history.receipts()) {
            check(receipt.kind() != null && receipt.payload() != null && receipt.outcome() != null
                    && receipt.gross() != null && receipt.detail() != null, "Incomplete receipt " + receipt.commandId());
            check(receipt.revision() >= 0 && receipt.revision() <= history.revision(),
                    "Receipt revision outside history for " + receipt.commandId());
            if (!isMutation(receipt.outcome())) {
                decisions.computeIfAbsent(receipt.revision(), ignored -> new ArrayList<>()).add(receipt);
            }
        }
    }

    private Summary replay() {
        BigInteger openingTotal = sumBalances();
        check(history.events() != null, "Missing event history");
        checkDecisions();
        for (var event : history.events()) {
            revision++;
            check(event != null && event.revision() == revision, "Missing or out-of-order event");
            var receipt = receipts.get(event.commandId());
            check(receipt != null && receipt.revision() == revision && receipt.kind().equals(event.kind()),
                    "Event and receipt disagree for command " + event.commandId());
            check(mutations.add(event.commandId()), "Repeated event for command " + event.commandId());
            var ids = event.paymentIds();
            check(ids != null && !ids.isEmpty() && ids.stream().noneMatch(Objects::isNull), "Missing event members");
            switch (receipt.kind()) {
                case "SUBMIT" -> submit(receipt, singleMember(ids));
                case "CANCEL" -> cancel(receipt, singleMember(ids));
                case "SETTLE" -> settle(receipt, ids);
                default -> throw invalid("Unknown event kind " + receipt.kind());
            }
            checkDecisions();
        }
        check(revision == history.revision(), "Event count disagrees with domain revision " + history.revision());
        check(history.nextQueueSeq() == (long) states.size() + 1, "Next queue sequence disagrees with admissions");
        for (var receipt : receipts.values()) {
            check(mutations.contains(receipt.commandId()) == isMutation(receipt.outcome()),
                    "Missing or unexpected event for command " + receipt.commandId());
        }
        for (var batch : batches.values()) {
            check(committedBatches.contains(batch.id()), "Batch has no settlement event " + batch.id());
        }
        for (var payment : payments.values()) {
            check(states.containsKey(payment.id()) && states.get(payment.id()).equals(payment.status()),
                    "Materialized status disagrees for payment " + payment.id());
            check(Objects.equals(memberships.get(payment.id()), payment.batchId()),
                    "Materialized batch membership disagrees for payment " + payment.id());
        }
        for (var account : history.accounts()) {
            check(balances.get(account.id()).equals(BigInteger.valueOf(account.balance())),
                    "Materialized balance disagrees for account " + account.id());
        }
        check(sumBalances().equals(openingTotal), "Closing balances do not conserve the opening total");
        int cancelled = (int) states.values().stream().filter("CANCELLED"::equals).count();
        return new Summary(revision, states.size(), memberships.size(), cancelled, committedBatches.size(),
                gross, openingTotal);
    }

    private void submit(History.Receipt receipt, UUID id) {
        var payment = payments.get(id);
        check(payment != null, "Admission has no payment row " + id);
        check("QUEUED".equals(receipt.outcome()) && receipt.batchId() == null && receipt.gross().signum() == 0,
                "Invalid admission receipt for payment " + id);
        check(payment.amount() > 0 && balances.containsKey(payment.from()) && balances.containsKey(payment.to())
                && !payment.from().equals(payment.to()), "Invalid transfer terms for payment " + id);
        check(payload(payment).equals(receipt.payload()), "Admission receipt and payment terms disagree for " + id);
        check(payment.queueSeq() == (long) states.size() + 1, "Unexpected queue sequence for payment " + id);
        check(states.putIfAbsent(id, "QUEUED") == null, "Payment admitted twice " + id);
        queue.add(id);
    }

    private void cancel(History.Receipt receipt, UUID id) {
        check("CANCELLED".equals(receipt.outcome()) && receipt.batchId() == null && receipt.gross().signum() == 0
                && receipt.payload().equals("payment=" + id), "Invalid cancellation receipt for payment " + id);
        check("QUEUED".equals(states.get(id)), "Cancelled payment was not queued " + id);
        states.put(id, "CANCELLED");
        queue.remove(id);
    }

    private void settle(History.Receipt receipt, List<UUID> ids) {
        var batch = batches.get(receipt.batchId());
        check("COMMITTED".equals(receipt.outcome()) && batch != null && batch.commitRevision() == revision,
                "Missing committed batch for command " + receipt.commandId());
        check(batch.snapshotRevision() >= 0 && batch.snapshotRevision() < revision
                && ReceiptPayload.knownPolicy(batch.policyVersion()), "Invalid proposal for batch " + batch.id());
        check(committedBatches.add(batch.id()), "Repeated batch " + batch.id());
        check(ids.size() <= 128, "Oversized batch " + batch.id());
        var seen = new HashSet<UUID>();
        for (UUID id : ids) {
            check(seen.add(id), "Repeated payment " + id + " in batch " + batch.id());
        }
        String members = ids.stream().map(UUID::toString).sorted().collect(Collectors.joining(","));
        String command = "batch=" + batch.id() + ";snapshot=" + batch.snapshotRevision()
                + ";policy=" + batch.policyVersion() + ";members=" + members;
        check(receipt.payload().equals(command), "Receipt and event members or terms disagree for batch " + batch.id());
        if (ReceiptPayload.FIFO.equals(batch.policyVersion())) {
            String violation = fifoViolation(ids);
            if (violation != null) throw invalid(violation + " in batch " + batch.id());
        }
        BigInteger batchGross = BigInteger.ZERO;
        var changed = new HashSet<UUID>();
        for (UUID id : ids) {
            String prior = states.get(id);
            check("QUEUED".equals(prior), "Batch " + batch.id() + " contains payment " + id + " in state " + prior);
            var payment = payments.get(id);
            check(batch.id().equals(payment.batchId()), "Missing or incorrect batch membership for payment " + id);
            BigInteger amount = BigInteger.valueOf(payment.amount());
            balances.merge(payment.from(), amount.negate(), BigInteger::add);
            balances.merge(payment.to(), amount, BigInteger::add);
            changed.add(payment.from());
            changed.add(payment.to());
            states.put(id, "SETTLED");
            queue.remove(id);
            memberships.put(id, batch.id());
            batchGross = batchGross.add(amount);
        }
        for (UUID account : changed) {
            var balance = balances.get(account);
            check(balance.signum() >= 0 && balance.compareTo(MAX_BALANCE) <= 0,
                    "Balance outside the allowed range for account " + account + " in batch " + batch.id());
        }
        check(batchGross.equals(receipt.gross()), "Gross amount disagrees for batch " + batch.id());
        gross = gross.add(batchGross);
    }

    // A receipt with no state change was decided after the event at its recorded revision.
    private void checkDecisions() {
        for (var receipt : decisions.getOrDefault(revision, List.of())) {
            check(receipt.gross().signum() == 0, "Non-mutating command claims settled gross " + receipt.commandId());
            boolean valid;
            try {
                valid = switch (receipt.kind()) {
                    case "CANCEL" -> cancelDecision(receipt, ReceiptPayload.cancel(receipt.payload()));
                    case "SUBMIT" -> submitDecision(receipt, ReceiptPayload.submit(receipt.payload()));
                    case "SETTLE" -> batchDecision(receipt, ReceiptPayload.settle(receipt.payload()));
                    default -> false;
                };
            } catch (IllegalArgumentException malformed) {
                throw invalid("Invalid payload for command " + receipt.commandId() + ": " + malformed.getMessage());
            }
            check(valid, "Receipt outcome disagrees with history for command " + receipt.commandId());
        }
    }

    private boolean cancelDecision(History.Receipt receipt, UUID id) {
        String state = states.get(id);
        return switch (receipt.outcome()) {
            case "NOT_FOUND" -> state == null && receipt.batchId() == null;
            case "ALREADY_CANCELLED" -> "CANCELLED".equals(state) && receipt.batchId() == null;
            case "ALREADY_SETTLED" -> "SETTLED".equals(state) && memberships.get(id).equals(receipt.batchId());
            default -> false;
        };
    }

    private boolean submitDecision(History.Receipt receipt, ReceiptPayload.Admission terms) {
        if (receipt.batchId() != null) return false;
        if (states.containsKey(terms.payment())) {
            boolean same = payload(payments.get(terms.payment())).equals(receipt.payload());
            return receipt.outcome().equals(same ? "ALREADY_EXISTS" : "PAYMENT_ID_CONFLICT");
        }
        boolean missingAccount = !balances.containsKey(terms.from()) || !balances.containsKey(terms.to());
        return missingAccount && receipt.outcome().equals("REJECTED");
    }

    private boolean batchDecision(History.Receipt receipt, ReceiptPayload.Proposal proposal) {
        boolean rejected = receipt.outcome().equals("REJECTED") && receipt.batchId() == null;
        if (proposal.snapshot() < 0 || proposal.snapshot() > revision) return rejected;
        if (committedBatches.contains(proposal.batch())) {
            return receipt.outcome().equals("BATCH_ID_CONFLICT") && proposal.batch().equals(receipt.batchId());
        }
        if (!rejected) return false;
        if (ReceiptPayload.FIFO.equals(proposal.policy())) return fifoViolation(proposal.members()) != null;
        var deltas = new HashMap<UUID, BigInteger>();
        for (UUID id : proposal.members()) {
            if (!"QUEUED".equals(states.get(id))) return true;
            var payment = payments.get(id);
            BigInteger amount = BigInteger.valueOf(payment.amount());
            deltas.merge(payment.from(), amount.negate(), BigInteger::add);
            deltas.merge(payment.to(), amount, BigInteger::add);
        }
        return deltas.entrySet().stream().anyMatch(entry -> {
            BigInteger closing = balances.get(entry.getKey()).add(entry.getValue());
            return closing.signum() < 0 || closing.compareTo(MAX_BALANCE) > 0;
        });
    }

    // Use the queue reconstructed from admissions and terminal events at this revision.
    // Receipt member order is canonical by ID, independent of the persisted queue sequence.
    private String fifoViolation(List<UUID> members) {
        var requested = new HashSet<>(members);
        var head = queue.iterator();
        var running = new HashMap<UUID, BigInteger>();
        for (int i = 0; i < members.size(); i++) {
            if (!head.hasNext()) return "FIFO proposal exceeds the queued prefix";
            UUID id = head.next();
            if (!requested.contains(id)) return "FIFO proposal skips queued payment " + id;
            var payment = payments.get(id);
            BigInteger amount = BigInteger.valueOf(payment.amount());
            BigInteger debit = running.getOrDefault(payment.from(), balances.get(payment.from())).subtract(amount);
            BigInteger credit = running.getOrDefault(payment.to(), balances.get(payment.to())).add(amount);
            if (debit.signum() < 0 || credit.compareTo(MAX_BALANCE) > 0) {
                return "FIFO payment " + id + " would put an intermediate balance outside its allowed range";
            }
            running.put(payment.from(), debit);
            running.put(payment.to(), credit);
        }
        return null;
    }

    private UUID singleMember(List<UUID> ids) {
        check(ids.size() == 1, "Expected a single payment in event");
        return ids.getFirst();
    }

    private static String payload(History.Payment payment) {
        return "payment=" + payment.id() + ";from=" + payment.from() + ";to=" + payment.to()
                + ";amount=" + payment.amount();
    }

    private BigInteger sumBalances() {
        return balances.values().stream().reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static boolean isMutation(String outcome) {
        return outcome.equals("QUEUED") || outcome.equals("CANCELLED") || outcome.equals("COMMITTED");
    }

    private <T> Map<UUID, T> index(List<T> records, Function<T, UUID> key, String type) {
        check(records != null, "Missing " + type + " list");
        var map = new HashMap<UUID, T>();
        for (T record : records) {
            check(record != null && key.apply(record) != null, "Missing " + type + " identity");
            UUID id = key.apply(record);
            check(map.put(id, record) == null, "Duplicate " + type + " " + id);
        }
        return map;
    }

    private void check(boolean condition, String message) {
        if (!condition) throw invalid(message);
    }

    private IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Revision " + revision + ": " + message);
    }
}
