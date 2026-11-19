package lab.settlement.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lab.settlement.domain.Payment;
import lab.settlement.selection.QueuedPayment;
import lab.settlement.selection.QueueSnapshot;
import lab.settlement.selection.SelectionPolicy;
import lab.settlement.settlement.Ledger;

/** Both policies receive the same immutable calendar and one opportunity per logical tick. */
public final class EconomicBenchmark {
    public static final long DEFAULT_SEED = 20261119L;
    public static final int DEFAULT_WINDOW = 32;

    public record Opening(String participant, UUID accountId, long balance) {}
    public record Tick(int tick, List<QueuedPayment> arrivals, List<UUID> cancellations) {}
    public record AgedPayment(UUID paymentId, int ageTicks) {}
    public record Cancellation(UUID paymentId, Ledger.CancelResult outcome) {}
    public record Decision(int tick, long snapshotRevision, int inspected, boolean hasMore,
                           List<Cancellation> cancellations, List<AgedPayment> settled, BigInteger gross) {}
    public record Participant(String name, UUID accountId, long closingBalance,
                              Map<Integer, Integer> settledWaitHistogram, Map<Integer, Integer> pendingAgeHistogram) {}
    public record Outcome(String policy, BigInteger grossSettled, int settledCount, int cancelledCount,
                          int batchCount, List<Decision> decisions, List<Participant> participants,
                          List<AgedPayment> pendingAtCutoff, Integer oldestPendingAgeTicks) {}
    public record Comparison(String workload, BigInteger initialLiquidity, List<Opening> openings,
                             List<Tick> schedule, List<Outcome> policies) {}
    public record Report(int formatVersion, long seed, int window, List<Comparison> workloads) {}

    private EconomicBenchmark() {}

    public static Report run(long seed, int window) {
        if (window < 1 || window > QueueSnapshot.MAX_WINDOW) {
            throw new IllegalArgumentException("Window must be between 1 and " + QueueSnapshot.MAX_WINDOW);
        }
        var comparisons = new ArrayList<Comparison>();
        for (var workload : EconomicWorkloads.generate(seed)) {
            var outcomes = new ArrayList<Outcome>();
            for (var policy : List.of(SelectionPolicy.FIFO, SelectionPolicy.HEURISTIC)) {
                var run = new Run(workload.openings());
                for (Tick tick : workload.schedule()) {
                    run.advance(tick, policy, window);
                }
                outcomes.add(run.finish(policy, workload.openings(), workload.schedule().getLast().tick()));
            }
            var initial = workload.openings().stream().map(o -> BigInteger.valueOf(o.balance()))
                    .reduce(BigInteger.ZERO, BigInteger::add);
            comparisons.add(new Comparison(workload.name(), initial, workload.openings(), workload.schedule(),
                    List.copyOf(outcomes)));
        }
        return new Report(1, seed, window, List.copyOf(comparisons));
    }

    public static void write(Report report, PrintStream out) throws IOException {
        out.print(new ObjectMapper().writeValueAsString(report));
        out.print('\n');
    }

    private record Admission(QueuedPayment payment, int tick) {}

    private static final class Run {
        private final Ledger ledger;
        private final Map<UUID, Admission> admissions = new HashMap<>();
        private final Map<UUID, Integer> settledWaits = new HashMap<>();
        private final List<Decision> decisions = new ArrayList<>();
        private long revision;
        private int batches;
        private BigInteger gross = BigInteger.ZERO;

        Run(List<Opening> openings) {
            var balances = new HashMap<String, Long>();
            openings.forEach(o -> balances.put(o.accountId().toString(), o.balance()));
            ledger = new Ledger(balances);
        }

        void advance(Tick tick, SelectionPolicy policy, int window) {
            for (var p : tick.arrivals()) {
                ledger.submit(new Payment(p.id().toString(), p.from().toString(), p.to().toString(), p.amount()));
                admissions.put(p.id(), new Admission(p, tick.tick()));
                revision++;
            }
            var cancellations = new ArrayList<Cancellation>();
            for (UUID id : tick.cancellations()) {
                var outcome = ledger.cancel(id.toString());
                cancellations.add(new Cancellation(id, outcome));
                if (outcome == Ledger.CancelResult.CANCELLED) revision++;
            }

            var snapshot = snapshot(window);
            var selection = policy.select(snapshot);
            var settled = new ArrayList<AgedPayment>();
            if (!selection.members().isEmpty()) {
                var ids = selection.ids().stream().map(UUID::toString).toList();
                var result = ledger.settle("tick-" + tick.tick(), ids);
                if (!result.committed() || !result.grossSettled().equals(selection.gross())) {
                    throw new IllegalStateException("Selected batch failed at tick " + tick.tick() + ": " + result.detail());
                }
                for (var p : selection.members()) {
                    int wait = tick.tick() - admissions.get(p.id()).tick();
                    settledWaits.put(p.id(), wait);
                    settled.add(new AgedPayment(p.id(), wait));
                }
                gross = gross.add(result.grossSettled());
                revision++;
                batches++;
            }
            decisions.add(new Decision(tick.tick(), snapshot.revision(), snapshot.payments().size(), snapshot.hasMore(),
                    List.copyOf(cancellations), List.copyOf(settled), selection.gross()));
        }

        private QueueSnapshot snapshot(int window) {
            var balances = new HashMap<UUID, Long>();
            ledger.balances().forEach((id, amount) -> balances.put(UUID.fromString(id), amount));
            var head = ledger.payments().values().stream()
                    .filter(e -> e.status() == Ledger.Status.QUEUED).limit(window + 1L)
                    .map(e -> admissions.get(UUID.fromString(e.payment().id())).payment()).toList();
            return new QueueSnapshot(revision, window, head.size() > window, balances,
                    head.subList(0, Math.min(window, head.size())));
        }

        Outcome finish(SelectionPolicy policy, List<Opening> openings, int cutoff) {
            var pending = new ArrayList<AgedPayment>();
            var waitsBySender = new HashMap<UUID, Map<Integer, Integer>>();
            var agesBySender = new HashMap<UUID, Map<Integer, Integer>>();
            int cancelled = 0;
            for (var entry : ledger.payments().values()) {
                UUID id = UUID.fromString(entry.payment().id());
                var admission = admissions.get(id);
                UUID sender = admission.payment().from();
                switch (entry.status()) {
                    case CANCELLED -> cancelled++;
                    case SETTLED -> waitsBySender.computeIfAbsent(sender, k -> new TreeMap<>())
                            .merge(settledWaits.get(id), 1, Integer::sum);
                    case QUEUED -> {
                        int age = cutoff - admission.tick();
                        pending.add(new AgedPayment(id, age));
                        agesBySender.computeIfAbsent(sender, k -> new TreeMap<>()).merge(age, 1, Integer::sum);
                    }
                }
            }
            var balances = ledger.balances();
            var participants = openings.stream().map(o -> new Participant(o.participant(), o.accountId(),
                    balances.get(o.accountId().toString()), waitsBySender.getOrDefault(o.accountId(), Map.of()),
                    agesBySender.getOrDefault(o.accountId(), Map.of()))).toList();
            Integer oldest = pending.stream().map(AgedPayment::ageTicks).max(Integer::compareTo).orElse(null);
            return new Outcome(policy.version(), gross, settledWaits.size(), cancelled, batches, List.copyOf(decisions),
                    participants, List.copyOf(pending), oldest);
        }
    }
}
