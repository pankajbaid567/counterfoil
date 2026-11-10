package dev.counterfoil.payments;

import java.util.EnumSet;
import java.util.Set;

/** The payment state machine. Every legal transition is listed here and nowhere else (ADR-007). */
public enum PaymentStatus {
    CREATED, AUTHORIZING, AUTHORIZED, CAPTURING, CAPTURED, PARTIALLY_REFUNDED, REFUNDED, VOIDING, VOIDED, FAILED;

    public Set<PaymentStatus> next() {
        return switch (this) {
            case CREATED -> EnumSet.of(AUTHORIZING);
            case AUTHORIZING -> EnumSet.of(AUTHORIZED, FAILED);
            case AUTHORIZED -> EnumSet.of(CAPTURING, VOIDING);
            case CAPTURING -> EnumSet.of(CAPTURED, FAILED);
            case CAPTURED -> EnumSet.of(PARTIALLY_REFUNDED, REFUNDED);
            case PARTIALLY_REFUNDED -> EnumSet.of(PARTIALLY_REFUNDED, REFUNDED);
            case VOIDING -> EnumSet.of(VOIDED);
            case REFUNDED, VOIDED, FAILED -> EnumSet.noneOf(PaymentStatus.class);
        };
    }

    public boolean canMoveTo(PaymentStatus to) {
        return next().contains(to);
    }

    public boolean isTerminal() {
        return next().isEmpty();
    }

    /** Statuses where money has been captured and not fully refunded. */
    public boolean isRefundable() {
        return this == CAPTURED || this == PARTIALLY_REFUNDED;
    }
}
