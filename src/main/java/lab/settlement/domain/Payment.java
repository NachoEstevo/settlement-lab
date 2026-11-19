package lab.settlement.domain;

/** An obligation in whole minor units, with terms fixed at submission. */
public record Payment(String id, String from, String to, long amount) {
    public Payment {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A payment needs an ID.");
        }
        if (from == null || from.isBlank() || to == null || to.isBlank() || from.equals(to)) {
            throw new IllegalArgumentException("A payment needs two distinct accounts.");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive.");
        }
    }
}
