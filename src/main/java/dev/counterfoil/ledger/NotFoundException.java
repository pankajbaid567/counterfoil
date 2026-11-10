package dev.counterfoil.ledger;

public class NotFoundException extends LedgerException {
    public NotFoundException(String message) {
        super("NOT_FOUND", 404, message);
    }
}
