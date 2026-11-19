package lab.settlement.cli;

import java.io.PrintStream;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lab.settlement.demo.PgRace;
import lab.settlement.postgres.PgAuthority;
import lab.settlement.verification.History;
import lab.settlement.verification.HistoryVerifier;

final class PgOutput {
    private static final Map<UUID, String> FIXTURE_LABELS = Map.of(
            PgRace.A, "A", PgRace.B, "B", PgRace.C, "C", PgRace.AB, "AB", PgRace.BC, "BC", PgRace.CA, "CA");

    private PgOutput() {}

    static void proposal(PrintStream out, String order, PgRace.Proposal proposal) {
        out.println("order=" + order);
        out.println("domain=" + proposal.domain());
        out.println("settle_command=" + PgRace.SETTLE_COMMAND);
        out.println("cancel_command=" + PgRace.CANCEL_COMMAND);
        out.println("batch=" + PgRace.BATCH);
        out.println("snapshot_revision=" + proposal.revision());
        out.println("members=" + proposal.members().stream().map(UUID::toString).collect(Collectors.joining(" ")));
        out.flush();
    }

    static void race(PrintStream out, PgRace.Result result) {
        var lock = result.contention();
        out.println("holder_pid=" + lock.holderPid());
        out.println("waiter_pid=" + lock.waiterPid());
        out.println("observer_pid=" + lock.observerPid());
        out.println("wait_event_type=" + lock.waitEventType());
        out.println("wait_event=" + lock.waitEvent());
        out.print("Settlement: ");
        receipt(out, result.settlement());
        out.print("Cancellation: ");
        receipt(out, result.cancellation());
        out.println("reply_recovered=" + result.replyRecovered());
    }

    static void receipt(PrintStream out, PgAuthority.Receipt result) {
        out.printf("outcome=%s revision=%s batch=%s gross=%s detail=%s%n",
                result.outcome(), result.revision(), batch(result.batchId()), result.gross(), result.detail());
    }

    static String batch(UUID batchId) {
        return batchId == null ? "-" : batchId.toString();
    }

    static void history(PrintStream out, History history, boolean fixture) {
        var verified = HistoryVerifier.verify(history);
        Map<UUID, String> labels = fixture ? FIXTURE_LABELS : Map.of();
        for (var account : history.accounts()) {
            out.printf("account=%s opening=%s balance=%s%n",
                    labels.getOrDefault(account.id(), account.id().toString()), account.opening(), account.balance());
        }
        for (var p : history.payments()) {
            out.printf("payment=%s from=%s to=%s amount=%s state=%s batch=%s%n",
                    labels.getOrDefault(p.id(), p.id().toString()),
                    labels.getOrDefault(p.from(), p.from().toString()),
                    labels.getOrDefault(p.to(), p.to().toString()), p.amount(), p.status(), batch(p.batchId()));
        }
        summary(out, verified);
    }

    static void summary(PrintStream out, HistoryVerifier.Summary s) {
        out.printf("VERIFIED revision=%s payments=%s settled=%s cancelled=%s batches=%s gross=%s balance=%s%n",
                s.revision(), s.payments(), s.settled(), s.cancelled(), s.batches(), s.grossSettled(), s.totalBalance());
    }
}
