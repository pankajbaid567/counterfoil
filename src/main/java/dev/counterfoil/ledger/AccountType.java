package dev.counterfoil.ledger;

public enum AccountType {
    ASSET(Side.D), EXPENSE(Side.D), LIABILITY(Side.C), EQUITY(Side.C), REVENUE(Side.C);

    private final Side normalSide;

    AccountType(Side normalSide) {
        this.normalSide = normalSide;
    }

    public Side normalSide() {
        return normalSide;
    }
}
