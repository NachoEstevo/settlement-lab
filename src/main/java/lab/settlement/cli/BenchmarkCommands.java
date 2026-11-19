package lab.settlement.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.sql.SQLException;
import java.util.List;
import lab.settlement.benchmark.CapacityBenchmark;
import lab.settlement.benchmark.EconomicBenchmark;
import lab.settlement.postgres.Database;

final class BenchmarkCommands {
    private BenchmarkCommands() {}

    static int economic(List<String> args, PrintStream out, PrintStream err) {
        if (args.size() < 2 || args.size() > 4 || !args.get(1).equals("economic")) {
            err.println("Usage: benchmark economic [SEED [WINDOW]]");
            return 2;
        }
        try {
            long seed = args.size() >= 3 ? Long.parseLong(args.get(2)) : EconomicBenchmark.DEFAULT_SEED;
            int window = args.size() == 4 ? Integer.parseInt(args.get(3)) : EconomicBenchmark.DEFAULT_WINDOW;
            EconomicBenchmark.write(EconomicBenchmark.run(seed, window), out);
            return 0;
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return 2;
        } catch (IOException e) {
            err.println("Could not write the benchmark report: " + e.getMessage());
            return 1;
        }
    }

    static void capacity(List<String> args, Database db, PrintStream out) throws SQLException {
        if (args.size() == 2) {
            CapacityBenchmark.run(db, 4, 96, 8, 42, out);
        } else if (args.size() == 6) {
            CapacityBenchmark.run(db, Integer.parseInt(args.get(2)), Integer.parseInt(args.get(3)),
                    Integer.parseInt(args.get(4)), Long.parseLong(args.get(5)), out);
        } else {
            throw new IllegalArgumentException("Usage: pg capacity [WORKERS PAYMENTS WINDOW SEED]");
        }
    }
}
