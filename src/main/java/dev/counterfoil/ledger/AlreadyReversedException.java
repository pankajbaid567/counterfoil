package dev.counterfoil.ledger;

public class AlreadyReversedException extends LedgerException {
    public AlreadyReversedException(String message) {
        super("ALREADY_REVERSED", 409, message);
    }
}
