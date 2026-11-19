package lab.settlement.cli;

import java.io.PrintStream;
import java.util.List;

public final class Main {
    private static final String HELP = """
            docker compose up -d --wait
            ./gradlew -q run --args="pg race settle-first --drop-reply"

            PostgreSQL 18 (each race creates a fresh domain):
            pg race cancel-first|settle-first [--drop-reply]
            pg migrate
            pg init DOMAIN ACCOUNT=OPENING...
            pg submit DOMAIN COMMAND PAYMENT FROM TO AMOUNT
            pg cancel DOMAIN COMMAND PAYMENT
            pg settle DOMAIN COMMAND BATCH SNAPSHOT_REVISION PAYMENT...
            pg receipt DOMAIN COMMAND
            pg payment DOMAIN PAYMENT
            pg snapshot DOMAIN
            pg export DOMAIN FILE
            verify FILE                      Verify an exported history offline.

            IDs are UUIDs. Amounts and revisions are whole decimal integers.
            Set SETTLEMENT_JDBC_URL, SETTLEMENT_DB_USER and SETTLEMENT_DB_PASSWORD
            to connect to a different lab database.

            In-memory examples (each command starts with a fresh ledger):
            demo cycle                       Settle the equal cycle, then try to cancel C's payment.
            demo cycle --race settle-first   Run the same order explicitly.
            demo cycle --race cancel-first   Cancel C's payment before proposing the group.
            demo unequal                     Propose a 100/100/90 cycle with zero opening funds.
            --help, -h                       Show this help.

            The in-memory examples use sequential order. The pg race waits until
            PostgreSQL reports that one transaction is blocking the other.
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
        if (command.getFirst().equals("pg") || command.getFirst().equals("verify")) {
            return PgCommands.run(command, out, err);
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
