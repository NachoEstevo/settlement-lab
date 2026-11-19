package lab.settlement.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import lab.settlement.benchmark.EconomicBenchmark.Opening;
import lab.settlement.benchmark.EconomicBenchmark.Tick;
import lab.settlement.selection.QueuedPayment;

final class EconomicWorkloads {
    record Workload(String name, List<Opening> openings, List<Tick> schedule) {}

    private static final UUID A = account(1), B = account(2), C = account(3), D = account(4);
    private static final int CUTOFF = 8;
    private final List<Tick> ticks = new ArrayList<>();
    private long sequence;

    static List<Workload> generate(long seed) {
        return List.of(liquid(new Random(seed)), rings(new Random(seed)), asymmetric(new Random(seed)));
    }

    private static Workload liquid(Random rng) {
        var fixture = new EconomicWorkloads();
        List<UUID> accounts = List.of(A, B, C, D);
        for (int tick = 1; tick <= CUTOFF; tick++) {
            var arrivals = new ArrayList<QueuedPayment>();
            int count = tick == 1 ? 36 : 3;
            for (int i = 0; i < count; i++) {
                int from = rng.nextInt(accounts.size());
                int to = (from + 1 + rng.nextInt(accounts.size() - 1)) % accounts.size();
                arrivals.add(fixture.payment(accounts.get(from), accounts.get(to), 1 + rng.nextInt(9)));
            }
            fixture.tick(arrivals, tick == 2 ? List.of(paymentId(36)) : List.of());
        }
        return fixture.workload("liquid", 1000, 1000, 1000, 1000);
    }

    private static Workload rings(Random rng) {
        var fixture = new EconomicWorkloads();
        for (int tick = 1; tick <= CUTOFF; tick++) {
            long amount = tick == 1 ? 100 : 50 + rng.nextInt(51);
            fixture.tick(List.of(fixture.payment(A, B, amount), fixture.payment(B, C, amount),
                    fixture.payment(C, A, amount)), tick == 3 ? List.of(paymentId(3)) : List.of());
        }
        return fixture.workload("blocked-rings", 20, 20, 20);
    }

    private static Workload asymmetric(Random rng) {
        var fixture = new EconomicWorkloads();
        fixture.tick(List.of(fixture.payment(A, B, 4), fixture.payment(A, C, 5)), List.of());
        fixture.tick(List.of(fixture.payment(B, A, 4), fixture.payment(D, A, 200)), List.of());
        for (int tick = 3; tick <= CUTOFF; tick++) {
            long amount = 10 + rng.nextInt(21);
            fixture.tick(List.of(fixture.payment(B, C, amount), fixture.payment(C, B, amount)),
                    tick == 3 ? List.of(paymentId(1)) : List.of());
        }
        return fixture.workload("asymmetric", 5, 0, 0, 0);
    }

    private QueuedPayment payment(UUID from, UUID to, long amount) {
        sequence++;
        return new QueuedPayment(paymentId(sequence), from, to, amount, sequence);
    }

    private void tick(List<QueuedPayment> arrivals, List<UUID> cancellations) {
        ticks.add(new Tick(ticks.size() + 1, List.copyOf(arrivals), List.copyOf(cancellations)));
    }

    private Workload workload(String name, long... balances) {
        var openings = new ArrayList<Opening>();
        for (int i = 0; i < balances.length; i++) {
            openings.add(new Opening(String.valueOf((char) ('A' + i)), account(i + 1), balances[i]));
        }
        return new Workload(name, List.copyOf(openings), List.copyOf(ticks));
    }

    private static UUID account(long index) { return new UUID(0, index); }
    private static UUID paymentId(long index) { return new UUID(1, index); }
}
