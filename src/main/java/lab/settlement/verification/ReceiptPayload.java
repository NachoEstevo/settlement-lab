package lab.settlement.verification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Decoder for the normalized command fields stored in version 1 history. */
final class ReceiptPayload {
    record Admission(UUID payment, UUID from, UUID to, long amount) {}
    record Proposal(UUID batch, long snapshot, List<UUID> members) {}

    private ReceiptPayload() {}

    static UUID cancel(String payload) {
        return uuid(fields(payload, "payment")[0]);
    }

    static Admission submit(String payload) {
        var fields = fields(payload, "payment", "from", "to", "amount");
        var terms = new Admission(uuid(fields[0]), uuid(fields[1]), uuid(fields[2]), number(fields[3]));
        if (terms.amount() <= 0 || terms.from().equals(terms.to())) throw new IllegalArgumentException("Invalid transfer terms");
        return terms;
    }

    static Proposal settle(String payload) {
        var fields = fields(payload, "batch", "snapshot", "policy", "members");
        if (!fields[2].equals("multilateral-v1")) throw new IllegalArgumentException("Unknown policy");
        String[] rawIds = fields[3].split(",", -1);
        if (rawIds.length > 128) throw new IllegalArgumentException("Too many members");
        var members = new ArrayList<UUID>(rawIds.length);
        for (int i = 0; i < rawIds.length; i++) {
            if (i > 0 && rawIds[i - 1].compareTo(rawIds[i]) >= 0) {
                throw new IllegalArgumentException("Members must be unique and in canonical order");
            }
            members.add(uuid(rawIds[i]));
        }
        return new Proposal(uuid(fields[0]), number(fields[1]), List.copyOf(members));
    }

    private static String[] fields(String payload, String... names) {
        String[] parts = payload.split(";", -1);
        if (parts.length != names.length) throw new IllegalArgumentException("Unexpected field count");
        for (int i = 0; i < names.length; i++) {
            String prefix = names[i] + "=";
            if (!parts[i].startsWith(prefix)) throw new IllegalArgumentException("Expected field " + names[i]);
            parts[i] = parts[i].substring(prefix.length());
        }
        return parts;
    }

    private static UUID uuid(String encoded) {
        UUID id = UUID.fromString(encoded);
        if (!id.toString().equals(encoded)) throw new IllegalArgumentException("Noncanonical UUID");
        return id;
    }

    private static long number(String encoded) {
        long n = Long.parseLong(encoded);
        if (!Long.toString(n).equals(encoded)) throw new IllegalArgumentException("Noncanonical number");
        return n;
    }
}
