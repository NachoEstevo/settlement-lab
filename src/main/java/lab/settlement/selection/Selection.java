package lab.settlement.selection;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

/** Selected members in queue order and the volume their batch would settle. */
public record Selection(List<QueuedPayment> members, BigInteger gross) {
    public Selection {
        members = List.copyOf(members);
        BigInteger sum = members.stream().map(p -> BigInteger.valueOf(p.amount()))
                .reduce(BigInteger.ZERO, BigInteger::add);
        if (!sum.equals(gross)) {
            throw new IllegalArgumentException("Gross volume must match the selected payments.");
        }
    }

    public List<UUID> ids() {
        return members.stream().map(QueuedPayment::id).toList();
    }
}
