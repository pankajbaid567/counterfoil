package dev.counterfoil.idempotency;

import dev.counterfoil.ledger.LedgerException;

public class IdempotencyException extends LedgerException {
    public IdempotencyException(String code, int status, String message) {
        super(code, status, message);
    }

    /** The same key was used with a different request body. */
    public static IdempotencyException mismatch() {
        return new IdempotencyException("IDEMPOTENCY_KEY_REUSED", 422, "this Idempotency-Key was already used with a different request");
    }

    /** The first request with this key has not finished. */
    public static IdempotencyException inProgress() {
        return new IdempotencyException("REQUEST_IN_PROGRESS", 409, "a request with this Idempotency-Key is still being processed; retry shortly");
    }
}
