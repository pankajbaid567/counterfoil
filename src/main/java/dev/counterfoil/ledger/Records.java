package dev.counterfoil.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Value types of the ledger API. Amounts are minor units (paise); there is no floating point anywhere. */
public final class Records {
    private Records() {}

    public record Account(long id, String code, String name, AccountType type, String currency, boolean allowNegative) {}

    public record LineRequest(String account, Side side, long amount) {}

    public record PostRequest(Kind kind, String reference, String description, List<LineRequest> lines) {
        public PostRequest(String reference, String description, List<LineRequest> lines) {
            this(Kind.MANUAL, reference, description, lines);
        }
    }

    public record LineView(String account, Side side, long amount, String currency) {}

    public record EntryView(long id, UUID publicId, Kind kind, String reference, String description, Instant postedAt,
                            Long reversesEntryId, List<LineView> lines) {}

    public record Balance(String account, String currency, AccountType type, Side normalSide, long debitTotal,
                          long creditTotal, long natural) {}

    public record StatementLine(long lineId, UUID entryId, Kind kind, String reference, Side side, long amount,
                                Instant postedAt) {}

    public record TrialBalanceRow(String account, String currency, long debitTotal, long creditTotal) {}

    public record CurrencyTotal(String currency, long debits, long credits) {}

    public record TrialBalance(List<TrialBalanceRow> rows, List<CurrencyTotal> totals, boolean balanced) {}

    public enum Kind { MANUAL, PAYMENT_CAPTURE, REFUND, PSP_SETTLEMENT, PAYOUT, REVERSAL }
}
