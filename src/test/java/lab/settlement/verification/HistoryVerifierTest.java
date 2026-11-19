package lab.settlement.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryVerifierTest {
    private static final UUID A = id(1), B = id(2), AB = id(3), BATCH = id(4);
    private static final UUID SUBMIT = id(5), SETTLE = id(6), CANCEL = id(7);
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;

    @Test
    void offlineReplayChecksTermsBalancesAndExactTotals() throws IOException {
        var file = directory.resolve("history.json");
        HistoryExport.write(history(), file);

        assertEquals(new HistoryVerifier.Summary(2, 1, 1, 0, 1, BigInteger.TEN,
                        BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.TEN)),
                HistoryVerifier.verify(file));
    }

    @Test
    void materializedPaymentMissingItsBatchIsIdentifiedAtSettlement() throws IOException {
        var export = fixture();
        first(export, "payments").putNull("batchId");
        checkInvalid(export, "Revision 2: Missing or incorrect batch membership for payment " + AB);
    }

    @Test
    void applyingTheDebitAndCreditTwiceCanConserveCashButChangesBothBalances() throws IOException {
        var export = fixture();
        first(export, "accounts").put("balance", Long.toString(Long.MAX_VALUE - 20));
        ((ObjectNode) export.withArray("accounts").get(1)).put("balance", "30");
        checkInvalid(export, "Revision 2: Materialized balance disagrees for account " + A);
    }

    @Test
    void replayIdentifiesAPreviouslyCancelledPaymentInsideABatch() throws IOException {
        var export = fixture();
        export.put("revision", 3);
        first(export, "batches").put("commitRevision", 3);
        ((ObjectNode) export.withArray("receipts").get(1)).put("revision", 3);
        export.withArray("receipts").add(JSON.valueToTree(new History.Receipt(CANCEL, "CANCEL", "payment=" + AB,
                "CANCELLED", "Cancelled", null, 2, BigInteger.ZERO)));
        var events = export.withArray("events");
        ((ObjectNode) events.get(1)).put("revision", 3);
        events.insert(1, JSON.valueToTree(new History.Event(2, CANCEL, "CANCEL", List.of(AB))));
        checkInvalid(export, "Revision 3: Batch " + BATCH + " contains payment " + AB + " in state CANCELLED");
    }

    @Test
    void repeatedMemberIsIdentifiedBeforeSettlement() throws IOException {
        var export = fixture();
        var members = (ArrayNode) export.withArray("events").get(1).get("paymentIds");
        members.add(AB.toString());
        checkInvalid(export, "Revision 2: Repeated payment " + AB);
    }

    @Test
    void eventAndMaterializedRowsCannotOverrideTheOriginalCommandMembers() throws IOException {
        var export = fixture();
        ((ObjectNode) export.withArray("receipts").get(1)).put("payload", batchPayload() + "," + id(99));
        checkInvalid(export, "Revision 2: Receipt and event members or terms disagree for batch " + BATCH);
    }

    @Test
    void alteredTermsDisagreeWithTheAdmissionReceipt() throws IOException {
        var export = fixture();
        first(export, "payments").put("amount", "11");
        checkInvalid(export, "Revision 1: Admission receipt and payment terms disagree for " + AB);
    }

    @Test
    void aFundedProposalCannotHaveARejectionReceipt() throws IOException {
        var export = fixture();
        export.withArray("receipts").add(JSON.valueToTree(new History.Receipt(id(9), "SETTLE", batchPayload(),
                "REJECTED", "Rejected", null, 1, BigInteger.ZERO)));
        checkInvalid(export, "Revision 1: Receipt outcome disagrees with history for command " + id(9));
    }

    @Test
    void aRejectedSubmissionMustContainValidTransferTerms() throws IOException {
        var export = fixture();
        export.withArray("receipts").add(JSON.valueToTree(new History.Receipt(id(9), "SUBMIT", "payment=" + AB,
                "PAYMENT_ID_CONFLICT", "Rejected", null, 1, BigInteger.ZERO)));
        checkInvalid(export, "Revision 1: Invalid payload for command " + id(9));
    }

    private ObjectNode fixture() throws IOException {
        Path file = directory.resolve("valid.json");
        HistoryExport.write(history(), file);
        return (ObjectNode) JSON.readTree(file.toFile());
    }

    private void checkInvalid(ObjectNode edited, String message) throws IOException {
        Path file = directory.resolve("corrupt.json");
        JSON.writeValue(file.toFile(), edited);
        var failure = assertThrows(IllegalArgumentException.class, () -> HistoryVerifier.verify(file));
        assertTrue(failure.getMessage().contains(message), failure.getMessage());
    }

    private static History history() {
        return new History(1, id(8), "SIM", 0, 2, 2,
                List.of(new History.Account(A, Long.MAX_VALUE, Long.MAX_VALUE - 10), new History.Account(B, 10, 20)),
                List.of(new History.Payment(AB, A, B, 10, 1, "SETTLED", BATCH)),
                List.of(new History.Batch(BATCH, 1, 2, "multilateral-v1")),
                List.of(new History.Receipt(SUBMIT, "SUBMIT", "payment=" + AB + ";from=" + A + ";to=" + B + ";amount=10",
                                "QUEUED", "Queued", null, 1, BigInteger.ZERO),
                        new History.Receipt(SETTLE, "SETTLE", batchPayload(), "COMMITTED", "Settled", BATCH, 2, BigInteger.TEN)),
                List.of(new History.Event(1, SUBMIT, "SUBMIT", List.of(AB)), new History.Event(2, SETTLE, "SETTLE", List.of(AB))));
    }

    private static ObjectNode first(ObjectNode root, String collection) {
        return (ObjectNode) root.withArray(collection).get(0);
    }

    private static String batchPayload() {
        return "batch=" + BATCH + ";snapshot=1;policy=multilateral-v1;members=" + AB;
    }

    private static UUID id(long number) {
        return new UUID(0, number);
    }
}
