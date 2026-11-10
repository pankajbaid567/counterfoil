package dev.counterfoil.payments;

import java.time.Instant;
import java.util.UUID;

/** API value types of the payment flows. */
public final class Payments {
    private Payments() {}

    public record CreateRequest(String merchantId, long amount, String currency) {}

    public record RefundRequest(long amount) {}

    public record PaymentView(UUID id, String merchantId, long amount, String currency, PaymentStatus status, long fee, long capturedAmount,
                              long refundedAmount, long refundReserved, String failureReason, long version, Instant createdAt, Instant updatedAt) {}

    public static class InvalidStateException extends dev.counterfoil.ledger.LedgerException {
        public InvalidStateException(String message) {
            super("INVALID_STATE", 409, message);
        }
    }

    public static class PaymentNotFoundException extends dev.counterfoil.ledger.NotFoundException {
        public PaymentNotFoundException(UUID id) {
            super("no payment " + id);
        }
    }
}
