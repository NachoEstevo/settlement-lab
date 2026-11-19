package lab.settlement.selection;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectionPropertyTest {
    private static final List<UUID> ACCOUNTS = List.of(new UUID(0, 1), new UUID(Long.MIN_VALUE, 1), new UUID(-1, 1));

    @Property(tries = 400, seed = "20261119")
    void selectedMembersAreFeasibleAndRankBetweenFifoAndTheExhaustiveOptimum(
            @ForAll("snapshots") QueueSnapshot snapshot) {
        var expectedFifo = SubsetOracle.fifo(snapshot);
        var fifo = SelectionPolicy.FIFO.select(snapshot);
        assertEquals(expectedFifo.members(), fifo.members());
        assertEquals(expectedFifo.gross(), fifo.gross());

        var selected = SelectionPolicy.HEURISTIC.select(snapshot);
        var orderedVisibleSubset = snapshot.payments().stream().filter(selected.members()::contains).toList();
        assertEquals(orderedVisibleSubset, selected.members());
        assertTrue(SubsetOracle.feasible(snapshot.balances(), selected.members()));
        var actual = SubsetOracle.Score.of(selected.members());
        assertEquals(actual.gross(), selected.gross());
        assertTrue(actual.compareTo(expectedFifo) >= 0);
        assertTrue(actual.compareTo(SubsetOracle.optimum(snapshot)) <= 0);
        assertEquals(selected, SelectionPolicy.HEURISTIC.select(snapshot));
    }

    private record Transfer(int route, long amount) {}

    @Provide
    Arbitrary<QueueSnapshot> snapshots() {
        var openings = Arbitraries.oneOf(Arbitraries.longs().between(0, 12),
                Arbitraries.of(Long.MAX_VALUE, Long.MAX_VALUE - 1, Long.MAX_VALUE - 4)).list().ofSize(3);
        var amount = Arbitraries.oneOf(Arbitraries.longs().between(1, 12),
                Arbitraries.of(Long.MAX_VALUE, Long.MAX_VALUE - 1));
        var transfers = Combinators.combine(Arbitraries.integers().between(0, 5), amount)
                .as(Transfer::new).list().ofMaxSize(12);
        return Combinators.combine(openings, transfers).as((cash, draws) -> {
            var queue = new ArrayList<QueuedPayment>();
            for (int i = 0; i < draws.size(); i++) {
                Transfer draw = draws.get(i);
                int from = draw.route() / 2;
                int to = (from + 1 + draw.route() % 2) % 3;
                long sequence = 5L * i + 2;
                queue.add(new QueuedPayment(new UUID(1, i), ACCOUNTS.get(from), ACCOUNTS.get(to), draw.amount(), sequence));
            }
            var balances = Map.of(ACCOUNTS.get(0), cash.get(0), ACCOUNTS.get(1), cash.get(1), ACCOUNTS.get(2), cash.get(2));
            return new QueueSnapshot(90, 12, false, balances, queue);
        });
    }
}
