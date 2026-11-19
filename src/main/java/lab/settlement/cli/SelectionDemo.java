package lab.settlement.cli;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lab.settlement.selection.QueueSnapshot;
import lab.settlement.selection.QueuedPayment;
import lab.settlement.selection.SelectionPolicy;

final class SelectionDemo {
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), C = new UUID(0, 3);

    private SelectionDemo() {}

    static void run(PrintStream out) {
        show(out, "Equal ring with 20 each. A->B 100, B->C 100, C->A 100.", 300,
                Map.of(A, 20L, B, 20L, C, 20L),
                List.of(payment(1, A, B, 100), payment(2, B, C, 100), payment(3, C, A, 100)));
        show(out, "Competing payments with A=5, B=C=0. A->B 4, then A->C 5.", 5,
                Map.of(A, 5L, B, 0L, C, 0L), List.of(payment(1, A, B, 4), payment(2, A, C, 5)));
    }

    private static QueuedPayment payment(int seq, UUID from, UUID to, long amount) {
        return new QueuedPayment(new UUID(1, seq), from, to, amount, seq);
    }

    private static void show(PrintStream out, String title, int optimum,
                             Map<UUID, Long> openings, List<QueuedPayment> queue) {
        var snapshot = new QueueSnapshot(queue.size(), queue.size(), false, openings, queue);
        out.println(title);
        out.println("Hand-calculated optimum=" + optimum);
        for (var policy : SelectionPolicy.values()) {
            var selected = policy.select(snapshot);
            var gap = BigInteger.valueOf(optimum).subtract(selected.gross());
            var percent = new BigDecimal(gap).movePointRight(2).divide(BigDecimal.valueOf(optimum));
            out.printf("%s queue_positions=%s gross=%s gap=%s (%s%%)%n", policy.version(),
                    selected.members().stream().map(QueuedPayment::queueSeq).toList(),
                    selected.gross(), gap, percent.toPlainString());
        }
    }
}
