package dev.counterfoil.ledger;

public class InsufficientFundsException extends LedgerException {
    public InsufficientFundsException(String message) {
        super("INSUFFICIENT_FUNDS", 409, message);
    }
}
