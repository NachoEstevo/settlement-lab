package lab.settlement.verification;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

/** A consistent export of the facts for one domain. Monetary fields use decimal strings on disk. */
public record History(int version, UUID domainId, String currency, int scale, long revision, long nextQueueSeq,
                      List<Account> accounts, List<Payment> payments, List<Batch> batches,
                      List<Receipt> receipts, List<Event> events) {
    public record Account(UUID id,
                          @JsonFormat(shape = JsonFormat.Shape.STRING)
                          @JsonDeserialize(using = MoneyDeserializer.LongUnits.class) long opening,
                          @JsonFormat(shape = JsonFormat.Shape.STRING)
                          @JsonDeserialize(using = MoneyDeserializer.LongUnits.class) long balance) {}

    public record Payment(UUID id, UUID from, UUID to,
                          @JsonFormat(shape = JsonFormat.Shape.STRING)
                          @JsonDeserialize(using = MoneyDeserializer.LongUnits.class) long amount,
                          long queueSeq, String status, UUID batchId) {}

    public record Batch(UUID id, long snapshotRevision, long commitRevision, String policyVersion) {}

    public record Receipt(UUID commandId, String kind, String payload, String outcome, String detail,
                          UUID batchId, long revision, @JsonFormat(shape = JsonFormat.Shape.STRING)
                          @JsonDeserialize(using = MoneyDeserializer.GrossUnits.class) BigInteger gross) {}

    public record Event(long revision, UUID commandId, String kind, List<UUID> paymentIds) {}
}
