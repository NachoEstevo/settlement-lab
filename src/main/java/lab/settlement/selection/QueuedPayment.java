package lab.settlement.selection;

import java.util.Objects;
import java.util.UUID;

/** Immutable payment terms and the domain's admission sequence. */
public record QueuedPayment(UUID id, UUID from, UUID to, long amount, long queueSeq) {
    public QueuedPayment {
        Objects.requireNonNull(id);
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        if (from.equals(to) || amount <= 0 || queueSeq < 1) {
            throw new IllegalArgumentException("A queued payment needs distinct accounts, a positive amount and sequence.");
        }
    }
}
