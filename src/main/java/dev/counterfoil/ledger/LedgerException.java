package dev.counterfoil.ledger;

/** Base type for expected, domain-level ledger failures. Each carries a stable machine-readable code. */
public class LedgerException extends RuntimeException {
    private final String code;
    private final int httpStatus;

    public LedgerException(String code, int httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
