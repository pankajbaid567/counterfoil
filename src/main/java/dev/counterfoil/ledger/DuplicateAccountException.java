package dev.counterfoil.ledger;

public class DuplicateAccountException extends LedgerException {
    public DuplicateAccountException(String message) {
        super("DUPLICATE_ACCOUNT", 409, message);
    }
}
