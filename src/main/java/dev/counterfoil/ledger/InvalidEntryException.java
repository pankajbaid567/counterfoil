package dev.counterfoil.ledger;

public class InvalidEntryException extends LedgerException {
    public InvalidEntryException(String message) {
        super("INVALID_ENTRY", 422, message);
    }
}
