package dev.counterfoil.ledger;

public enum Side {
    D, C;

    public Side opposite() {
        return this == D ? C : D;
    }

    public static Side parse(String s) {
        return switch (s == null ? "" : s.trim().toUpperCase()) {
            case "D", "DEBIT" -> D;
            case "C", "CREDIT" -> C;
            default -> throw new InvalidEntryException("side must be D or C (or DEBIT/CREDIT), got '" + s + "'");
        };
    }
}
