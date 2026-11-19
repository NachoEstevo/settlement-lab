package lab.settlement.cli;

import java.io.PrintStream;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lab.settlement.domain.Payment;
import lab.settlement.settlement.Ledger;

enum Demo {
    SETTLE_FIRST("Equal cycle / settlement first", 20, 100),
    CANCEL_FIRST("Equal cycle / cancellation first", 20, 100),
    UNEQUAL("Unequal cycle", 0, 90);

    private static final List<String> CYCLE = List.of("AB", "BC", "CA");

    private final String title;
    private final long opening;
    private final long returnAmount;

    Demo(String title, long opening, long returnAmount) {
        this.title = title;
        this.opening = opening;
        this.returnAmount = returnAmount;
    }

    void run(PrintStream out) {
        Ledger ledger = new Ledger(Map.of("A", opening, "B", opening, "C", opening));
        ledger.submit(new Payment("AB", "A", "B", 100));
        ledger.submit(new Payment("BC", "B", "C", 100));
        ledger.submit(new Payment("CA", "C", "A", returnAmount));

        out.println(title);
        printBalances(out, "Opening", ledger.balances());
        String queued = ledger.payments().values().stream()
                .map(Ledger.Entry::payment)
                .map(p -> "%s %s->%s %s".formatted(p.id(), p.from(), p.to(), p.amount()))
                .collect(Collectors.joining(", "));
        out.println("Queued: " + queued);
        switch (this) {
            case SETTLE_FIRST -> {
                propose(out, ledger, "single", List.of("AB"));
                propose(out, ledger, "cycle", CYCLE);
                cancelReturn(out, ledger);
            }
            case CANCEL_FIRST -> {
                cancelReturn(out, ledger);
                propose(out, ledger, "cycle", CYCLE);
                propose(out, ledger, "remainder", List.of("AB", "BC"));
            }
            case UNEQUAL -> propose(out, ledger, "cycle", CYCLE);
        }

        printBalances(out, "Closing", ledger.balances());
        for (Ledger.Entry entry : ledger.payments().values()) {
            String membership = entry.batchId() == null ? "" : " batch=" + entry.batchId();
            out.printf("  %s %s%s%n", entry.payment().id(), entry.status(), membership);
        }
    }

    private static void propose(PrintStream out, Ledger ledger, String batch, List<String> members) {
        Ledger.BatchResult result = ledger.settle(batch, members);
        String outcome = result.committed() ? "COMMITTED" : "REJECTED";
        out.printf("Batch %s %s: %s (gross settled=%s)", batch, members, outcome, result.grossSettled());
        if (!result.committed()) {
            out.print(" - " + result.detail());
        }
        out.println();
    }

    private static void cancelReturn(PrintStream out, Ledger ledger) {
        Ledger.CancelResult result = ledger.cancel("CA");
        String batch = ledger.payments().get("CA").batchId();
        out.printf("Cancel CA: %s%s%n", result, batch == null ? "" : " batch=" + batch);
    }

    private static void printBalances(PrintStream out, String stage, Map<String, Long> balances) {
        String accounts = balances.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(" "));
        BigInteger total = balances.values().stream()
                .map(BigInteger::valueOf)
                .reduce(BigInteger.ZERO, BigInteger::add);
        out.printf("%s balances (SIM): %s | total=%s%n", stage, accounts, total);
    }
}
