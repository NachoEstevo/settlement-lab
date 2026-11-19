package lab.settlement.cli;

import java.io.PrintStream;
import java.util.List;

public final class Main {
    private static final String HELP = """
            ./gradlew -q run --args="demo cycle"
            Windows PowerShell: .\\gradlew.bat -q run --args="demo cycle"

            Settlement Lab runs deterministic in-memory examples in simulated whole units (SIM).
            Each command starts with a fresh ledger.

            demo cycle                       Settle the equal cycle, then try to cancel C's payment.
            demo cycle --race settle-first   Run the same order explicitly.
            demo cycle --race cancel-first   Cancel C's payment before proposing the group.
            demo unequal                     Propose a 100/100/90 cycle with zero opening funds.
            --help, -h                       Show this help.

            The race option chooses a sequential order for the demo.
            """;

    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        List<String> command = List.of(args);
        if (command.isEmpty() || command.equals(List.of("--help")) || command.equals(List.of("-h"))) {
            out.print(HELP);
            return 0;
        }

        Demo demo;
        if (command.equals(List.of("demo", "cycle"))
                || command.equals(List.of("demo", "cycle", "--race", "settle-first"))) {
            demo = Demo.SETTLE_FIRST;
        } else if (command.equals(List.of("demo", "cycle", "--race", "cancel-first"))) {
            demo = Demo.CANCEL_FIRST;
        } else if (command.equals(List.of("demo", "unequal"))) {
            demo = Demo.UNEQUAL;
        } else {
            err.println("Unknown command. Run with --help for the available examples.");
            return 2;
        }
        demo.run(out);
        return 0;
    }
}
