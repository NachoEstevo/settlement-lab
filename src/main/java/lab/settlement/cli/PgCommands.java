package lab.settlement.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lab.settlement.demo.PgRace;
import lab.settlement.postgres.Database;
import lab.settlement.postgres.PaymentLookup;
import lab.settlement.postgres.PgAuthority;
import lab.settlement.verification.HistoryExport;
import lab.settlement.verification.HistoryVerifier;
import org.flywaydb.core.api.FlywayException;

final class PgCommands {
    private PgCommands() {}

    static int run(List<String> args, PrintStream out, PrintStream err) {
        try {
            if (args.getFirst().equals("verify")) {
                require(args.size() == 2, "verify FILE");
                try {
                    PgOutput.summary(out, HistoryVerifier.verify(Path.of(args.get(1))));
                } catch (IllegalArgumentException e) {
                    err.println("Invalid history: " + e.getMessage());
                    return 1;
                }
            } else {
                runDatabaseCommand(args, out);
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return 2;
        } catch (PgAuthority.UnknownCommitException e) {
            err.println("Commit outcome unknown. Retry with the same domain, command ID and arguments.");
            return 1;
        } catch (SQLException | IOException | FlywayException e) {
            err.println("Operation failed: " + e.getMessage());
            return 1;
        }
    }

    private static void runDatabaseCommand(List<String> args, PrintStream out) throws SQLException, IOException {
        require(args.size() >= 2, "pg COMMAND (see --help)");
        Database db = Database.fromEnvironment();
        PgAuthority authority = new PgAuthority(db);
        switch (args.get(1)) {
            case "migrate" -> {
                require(args.size() == 2, "pg migrate");
                db.migrate();
                out.println("Schema is current.");
            }
            case "race" -> {
                require((args.size() == 3 || (args.size() == 4 && args.get(3).equals("--drop-reply")))
                                && List.of("cancel-first", "settle-first").contains(args.get(2)),
                        "pg race cancel-first|settle-first [--drop-reply]");
                db.migrate();
                var proposal = PgRace.prepare(db);
                PgOutput.proposal(out, args.get(2), proposal);
                var result = PgRace.run(db, proposal, args.get(2).equals("cancel-first"), args.size() == 4);
                PgOutput.race(out, result);
                PgOutput.history(out, HistoryExport.read(db, result.domainId()), true);
            }
            case "init" -> {
                require(args.size() >= 4, "pg init DOMAIN ACCOUNT=OPENING...");
                UUID domain = id(args.get(2));
                Map<UUID, Long> openings = new HashMap<>();
                for (String argument : args.subList(3, args.size())) {
                    String[] pair = argument.split("=", -1);
                    require(pair.length == 2, "pg init DOMAIN ACCOUNT=OPENING...");
                    UUID account = id(pair[0]);
                    if (openings.putIfAbsent(account, number(pair[1])) != null) {
                        throw new IllegalArgumentException("Duplicate opening account: " + account);
                    }
                }
                db.createDomain(domain, openings);
                out.println("domain=" + domain);
            }
            case "submit" -> {
                require(args.size() == 8, "pg submit DOMAIN COMMAND PAYMENT FROM TO AMOUNT");
                PgOutput.receipt(out, authority.submit(id(args.get(2)), id(args.get(3)), id(args.get(4)),
                        id(args.get(5)), id(args.get(6)), number(args.get(7))));
            }
            case "cancel" -> {
                require(args.size() == 5, "pg cancel DOMAIN COMMAND PAYMENT");
                PgOutput.receipt(out, authority.cancel(id(args.get(2)), id(args.get(3)), id(args.get(4))));
            }
            case "settle" -> {
                require(args.size() >= 7, "pg settle DOMAIN COMMAND BATCH SNAPSHOT_REVISION PAYMENT...");
                List<UUID> members = args.subList(6, args.size()).stream().map(PgCommands::id).toList();
                PgOutput.receipt(out, authority.settle(id(args.get(2)), id(args.get(3)), id(args.get(4)),
                        number(args.get(5)), members));
            }
            case "receipt" -> {
                require(args.size() == 4, "pg receipt DOMAIN COMMAND");
                authority.receipt(id(args.get(2)), id(args.get(3)))
                        .ifPresentOrElse(r -> PgOutput.receipt(out, r), () -> out.println("NOT_FOUND"));
            }
            case "payment" -> {
                require(args.size() == 4, "pg payment DOMAIN PAYMENT");
                PaymentLookup.find(db, id(args.get(2)), id(args.get(3))).ifPresentOrElse(
                        p -> out.printf("payment=%s from=%s to=%s amount=%s state=%s batch=%s%n",
                                p.id(), p.from(), p.to(), p.amount(), p.status(), PgOutput.batch(p.batchId())),
                        () -> out.println("NOT_FOUND"));
            }
            case "snapshot" -> {
                require(args.size() == 3, "pg snapshot DOMAIN");
                PgOutput.history(out, HistoryExport.read(db, id(args.get(2))), false);
            }
            case "export" -> {
                require(args.size() == 4, "pg export DOMAIN FILE");
                HistoryExport.write(db, id(args.get(2)), Path.of(args.get(3)));
                out.println("Exported " + args.get(3));
            }
            default -> throw new IllegalArgumentException("Unknown PostgreSQL command. Run with --help.");
        }
    }

    private static UUID id(String value) {
        UUID id = UUID.fromString(value);
        if (!id.toString().equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("Expected a standard UUID: " + value);
        }
        return id;
    }

    private static long number(String value) {
        if (!value.matches("0|[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("Expected a nonnegative decimal integer: " + value);
        }
        return Long.parseLong(value);
    }

    private static void require(boolean ok, String usage) {
        if (!ok) {
            throw new IllegalArgumentException("Usage: " + usage);
        }
    }
}
