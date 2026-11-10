package dev.counterfoil.api;

import dev.counterfoil.ledger.AccountType;
import dev.counterfoil.ledger.Side;
import java.time.Instant;
import java.util.List;

/** Request bodies. JSON is snake_case (see application.yml). Unknown fields are rejected. */
public final class Dtos {
    private Dtos() {}

    public record AccountBody(String code, String name, AccountType type, String currency, Boolean allowNegative) {}

    public record LineBody(String account, Side side, Long amount) {}

    public record EntryBody(String reference, String description, List<LineBody> lines) {}

    public record ReverseBody(String reference) {}

    public record PayoutBody(Long amount) {}

    public record BankLineBody(String reference, Long amount) {}

    public record ReconcileBody(Instant periodFrom, Instant periodTo, List<BankLineBody> lines) {}
}
