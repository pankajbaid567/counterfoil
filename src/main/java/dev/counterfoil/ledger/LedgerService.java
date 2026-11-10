package dev.counterfoil.ledger;

import dev.counterfoil.config.SqlErrors;
import dev.counterfoil.config.TxRunner;
import dev.counterfoil.ledger.Records.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * The double-entry ledger. The database enforces the rules (balance at COMMIT, immutability, no overdraft);
 * this service validates early for clean error messages, inserts lines in a fixed account order so that
 * row locks are always taken in the same order (no deadlocks), and maps database errors to domain errors.
 */
@Service
public class LedgerService {
    static final int MAX_LINES = 100;
    static final long MAX_AMOUNT = 100_000_000_000_000L;

    private final JdbcClient jdbc;
    private final TxRunner tx;

    public LedgerService(JdbcClient jdbc, TxRunner tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ accounts
    public Account createAccount(String code, String name, AccountType type, String currency, boolean allowNegative) {
        if (code == null || !code.matches("^[a-z0-9:_.\\-]{1,120}$")) {
            throw new InvalidEntryException("account code must be 1-120 characters of a-z 0-9 : _ . -");
        }
        if (currency == null || !currency.matches("^[A-Z]{3}$")) {
            throw new InvalidEntryException("currency must be a 3-letter upper-case code");
        }
        return translate(() -> tx.run(() -> {
            long id = jdbc.sql("""
                    INSERT INTO accounts (code, name, type, currency, allow_negative)
                    VALUES (:code, :name, :type, :cur, :neg) RETURNING id""")
                    .param("code", code).param("name", name == null ? code : name).param("type", type.name())
                    .param("cur", currency).param("neg", allowNegative)
                    .query(Long.class).single();
            return new Account(id, code, name == null ? code : name, type, currency, allowNegative);
        }));
    }

    /**
     * Returns the account, creating it if absent. One INSERT ... ON CONFLICT DO NOTHING, so it is race-safe and can
     * run inside a caller's transaction (a unique-violation error would have aborted that transaction).
     */
    public Account ensureAccount(String code, String name, AccountType type, String currency, boolean allowNegative) {
        if (code == null || !code.matches("^[a-z0-9:_.\\-]{1,120}$")) {
            throw new InvalidEntryException("account code must be 1-120 characters of a-z 0-9 : _ . -");
        }
        jdbc.sql("""
                INSERT INTO accounts (code, name, type, currency, allow_negative)
                VALUES (:code, :name, :type, :cur, :neg) ON CONFLICT (code) DO NOTHING""")
                .param("code", code).param("name", name == null ? code : name).param("type", type.name())
                .param("cur", currency).param("neg", allowNegative).update();
        return findAccount(code).orElseThrow();
    }

    public Optional<Account> findAccount(String code) {
        return jdbc.sql("SELECT id, code, name, type, currency, allow_negative FROM accounts WHERE code = :c")
                .param("c", code)
                .query((rs, i) -> new Account(rs.getLong(1), rs.getString(2), rs.getString(3), AccountType.valueOf(rs.getString(4)),
                        rs.getString(5).trim(), rs.getBoolean(6)))
                .optional();
    }

    public Balance balance(String code) {
        return jdbc.sql("""
                SELECT a.code, a.currency, a.type, b.normal_side, b.debit_total, b.credit_total
                  FROM accounts a JOIN account_balances b ON b.account_id = a.id WHERE a.code = :c""")
                .param("c", code)
                .query((rs, i) -> {
                    Side normal = Side.parse(rs.getString(4));
                    long d = rs.getLong(5), c = rs.getLong(6);
                    return new Balance(rs.getString(1), rs.getString(2).trim(), AccountType.valueOf(rs.getString(3)), normal, d, c,
                            normal == Side.D ? d - c : c - d);
                }).optional().orElseThrow(() -> new NotFoundException("no account '" + code + "'"));
    }

    // ------------------------------------------------------------------ posting
    /** Posts one entry in its own transaction. */
    public EntryView post(PostRequest req) {
        return translate(() -> tx.run(() -> postInTx(req)));
    }

    /**
     * Posts one entry inside the caller's open transaction, so a payment can write the ledger, its own status,
     * the outbox event and the idempotency record atomically.
     */
    public EntryView postInTx(PostRequest req) {
        try {
            return insertEntry(req, null);
        } catch (DataAccessException e) {
            throw map(e);       // e.g. an overdraft surfaces as InsufficientFundsException; the caller's transaction rolls back
        }
    }

    private EntryView insertEntry(PostRequest req, Long reversesEntryId) {
        List<Line> lines = validateAndNormalize(req);
        var entry = jdbc.sql("""
                INSERT INTO journal_entries (kind, reference, description, reverses_entry_id)
                VALUES (:kind, :ref, :desc, :rev) RETURNING id, public_id, posted_at""")
                .param("kind", req.kind().name()).param("ref", req.reference()).param("desc", req.description())
                .param("rev", reversesEntryId)
                .query((rs, i) -> new Object[]{rs.getLong(1), rs.getObject(2, UUID.class), rs.getTimestamp(3)}).single();
        long entryId = (Long) entry[0];
        int n = 1;
        for (Line l : lines) { // sorted by account id: row locks in the balance trigger are taken in one global order
            jdbc.sql("INSERT INTO journal_lines (entry_id, line_no, account_id, side, amount, currency) VALUES (:e, :n, :a, :s, :amt, :cur)")
                    .param("e", entryId).param("n", n++).param("a", l.accountId).param("s", l.side.name())
                    .param("amt", l.amount).param("cur", l.currency).update();
        }
        List<LineView> views = lines.stream().map(l -> new LineView(l.code, l.side, l.amount, l.currency)).toList();
        return new EntryView(entryId, (UUID) entry[1], req.kind(), req.reference(), req.description(),
                ((Timestamp) entry[2]).toInstant(), reversesEntryId, views);
    }

    /** Accounts owned by the payment flows. Their balances are tied to payment rows by invariant I4. */
    public static boolean isReserved(String code) {
        return code.startsWith("merchant:") || code.equals("psp_clearing") || code.equals("refunds:pending") || code.equals("fees:revenue")
                || code.equals("bank:settlement");
    }

    private record Line(long accountId, String code, Side side, long amount, String currency) {}

    private List<Line> validateAndNormalize(PostRequest req) {
        if (req == null || req.lines() == null || req.lines().size() < 2) {
            throw new InvalidEntryException("an entry needs at least 2 lines");
        }
        if (req.lines().size() > MAX_LINES) {
            throw new InvalidEntryException("an entry may have at most " + MAX_LINES + " lines");
        }
        if (req.reference() != null && req.reference().length() > 200) {
            throw new InvalidEntryException("reference is limited to 200 characters");
        }
        if (req.kind() == Kind.MANUAL) {
            for (LineRequest l : req.lines()) {
                if (l != null && l.account() != null && isReserved(l.account())) {
                    throw new InvalidEntryException("account '" + l.account() + "' is managed by payment flows and cannot be posted to manually");
                }
            }
        }
        // merge lines of the same account and side; an account on both sides of one entry is rejected
        Map<String, EnumMap<Side, Long>> merged = new TreeMap<>();
        for (LineRequest l : req.lines()) {
            if (l == null || l.account() == null || l.side() == null) {
                throw new InvalidEntryException("every line needs an account and a side");
            }
            if (l.amount() <= 0 || l.amount() > MAX_AMOUNT) {
                throw new InvalidEntryException("line amounts must be positive and at most " + MAX_AMOUNT + " minor units");
            }
            merged.computeIfAbsent(l.account(), k -> new EnumMap<>(Side.class)).merge(l.side(), l.amount(), Math::addExact);
        }
        for (var e : merged.entrySet()) {
            if (e.getValue().size() > 1) {
                throw new InvalidEntryException("account '" + e.getKey() + "' appears on both sides of one entry");
            }
        }
        Map<String, long[]> ids = new HashMap<>(); // code -> {id}
        Map<String, String> currencies = new HashMap<>();
        jdbc.sql("SELECT id, code, currency FROM accounts WHERE code IN (:codes)").param("codes", merged.keySet())
                .query((rs, i) -> {
                    ids.put(rs.getString(2), new long[]{rs.getLong(1)});
                    currencies.put(rs.getString(2), rs.getString(3).trim());
                    return null;
                }).list();
        for (String code : merged.keySet()) {
            if (!ids.containsKey(code)) {
                throw new NotFoundException("no account '" + code + "'");
            }
        }
        List<Line> out = new ArrayList<>();
        Map<String, Long> net = new HashMap<>();
        for (var e : merged.entrySet()) {
            Side side = e.getValue().keySet().iterator().next();
            long amount = e.getValue().get(side);
            String cur = currencies.get(e.getKey());
            out.add(new Line(ids.get(e.getKey())[0], e.getKey(), side, amount, cur));
            net.merge(cur, side == Side.D ? amount : -amount, Math::addExact);
        }
        net.forEach((cur, v) -> {
            if (v != 0) {
                throw new InvalidEntryException("entry is unbalanced in " + cur + ": debits minus credits = " + v);
            }
        });
        out.sort(Comparator.comparingLong(Line::accountId));
        return out;
    }

    // ------------------------------------------------------------------ reversal
    public EntryView reverse(UUID entryPublicId, String reference) {
        return translate(() -> tx.run(() -> reverseInTx(entryPublicId, reference)));
    }

    public EntryView reverseInTx(UUID entryPublicId, String reference) {
        try {
            return doReverse(entryPublicId, reference);
        } catch (DataAccessException e) {
            throw map(e);
        }
    }

    private EntryView doReverse(UUID entryPublicId, String reference) {
        EntryView original = findEntry(entryPublicId).orElseThrow(() -> new NotFoundException("no entry " + entryPublicId));
        if (original.kind() == Kind.REVERSAL) {
            throw new InvalidEntryException("a reversal cannot itself be reversed; post a new entry");
        }
        boolean already = jdbc.sql("SELECT EXISTS (SELECT 1 FROM journal_entries WHERE reverses_entry_id = :id)")
                .param("id", original.id()).query(Boolean.class).single();
        if (already) {
            throw new AlreadyReversedException("entry " + entryPublicId + " was already reversed");
        }
        List<LineRequest> flipped = original.lines().stream().map(l -> new LineRequest(l.account(), l.side().opposite(), l.amount())).toList();
        return insertEntry(new PostRequest(Kind.REVERSAL, reference != null ? reference : "reversal of " + entryPublicId,
                "reversal of " + entryPublicId, flipped), original.id());
    }

    // ------------------------------------------------------------------ queries
    public Optional<EntryView> findEntry(UUID publicId) {
        var head = jdbc.sql("SELECT id, public_id, kind, reference, description, posted_at, reverses_entry_id FROM journal_entries WHERE public_id = :p")
                .param("p", publicId)
                .query((rs, i) -> new Object[]{rs.getLong(1), rs.getObject(2, UUID.class), Kind.valueOf(rs.getString(3)), rs.getString(4),
                        rs.getString(5), rs.getTimestamp(6), rs.getObject(7, Long.class)}).optional();
        if (head.isEmpty()) {
            return Optional.empty();
        }
        Object[] h = head.get();
        List<LineView> lines = jdbc.sql("""
                SELECT a.code, l.side, l.amount, l.currency FROM journal_lines l JOIN accounts a ON a.id = l.account_id
                 WHERE l.entry_id = :e ORDER BY l.line_no""").param("e", h[0])
                .query((rs, i) -> new LineView(rs.getString(1), Side.parse(rs.getString(2)), rs.getLong(3), rs.getString(4).trim())).list();
        return Optional.of(new EntryView((Long) h[0], (UUID) h[1], (Kind) h[2], (String) h[3], (String) h[4], ((Timestamp) h[5]).toInstant(),
                (Long) h[6], lines));
    }

    public List<StatementLine> statement(String accountCode, long afterLineId, int limit) {
        if (findAccount(accountCode).isEmpty()) {
            throw new NotFoundException("no account '" + accountCode + "'");
        }
        return jdbc.sql("""
                SELECT l.id, e.public_id, e.kind, e.reference, l.side, l.amount, e.posted_at
                  FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id JOIN accounts a ON a.id = l.account_id
                 WHERE a.code = :c AND l.id > :after ORDER BY l.id LIMIT :lim""")
                .param("c", accountCode).param("after", afterLineId).param("lim", Math.max(1, Math.min(limit, 500)))
                .query((rs, i) -> new StatementLine(rs.getLong(1), rs.getObject(2, UUID.class), Kind.valueOf(rs.getString(3)), rs.getString(4),
                        Side.parse(rs.getString(5)), rs.getLong(6), rs.getTimestamp(7).toInstant())).list();
    }

    public TrialBalance trialBalance() {
        List<TrialBalanceRow> rows = jdbc.sql("""
                SELECT a.code, a.currency, b.debit_total, b.credit_total FROM accounts a JOIN account_balances b ON b.account_id = a.id
                 ORDER BY a.code""")
                .query((rs, i) -> new TrialBalanceRow(rs.getString(1), rs.getString(2).trim(), rs.getLong(3), rs.getLong(4))).list();
        Map<String, long[]> byCur = new TreeMap<>();
        for (TrialBalanceRow r : rows) {
            long[] t = byCur.computeIfAbsent(r.currency(), k -> new long[2]);
            t[0] = Math.addExact(t[0], r.debitTotal());
            t[1] = Math.addExact(t[1], r.creditTotal());
        }
        List<CurrencyTotal> totals = byCur.entrySet().stream().map(e -> new CurrencyTotal(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
        return new TrialBalance(rows, totals, totals.stream().allMatch(t -> t.debits() == t.credits()));
    }

    // ------------------------------------------------------------------ error translation
    <T> T translate(Supplier<T> work) {
        try {
            return work.get();
        } catch (LedgerException e) {
            throw e;
        } catch (DataAccessException | TransactionException e) {
            throw map(e);
        }
    }

    private RuntimeException map(RuntimeException e) {
        if (SqlErrors.constraint(e).filter("no_overdraft"::equals).isPresent()) {
            return new InsufficientFundsException("the posting would take an account below zero");
        }
        if (SqlErrors.isUniqueViolation(e, "journal_entries_reverses_entry_id_key")) {
            return new AlreadyReversedException("that entry was already reversed");
        }
        if (SqlErrors.isUniqueViolation(e, "accounts_code_key")) {
            return new DuplicateAccountException("an account with that code already exists");
        }
        if (SqlErrors.sqlState(e).filter("23514"::equals).isPresent()) {
            String msg = SqlErrors.psql(e).map(p -> p.getServerErrorMessage() != null ? p.getServerErrorMessage().getMessage() : p.getMessage()).orElse("check violated");
            return new InvalidEntryException(msg);
        }
        return e;
    }
}
