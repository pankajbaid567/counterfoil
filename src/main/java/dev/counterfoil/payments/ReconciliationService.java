package dev.counterfoil.payments;

import dev.counterfoil.config.TxRunner;
import dev.counterfoil.ledger.InvalidEntryException;
import dev.counterfoil.ledger.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Compares a bank statement with the ledger's {@code bank:settlement} account (ADR-009).
 * Both sides are reduced to one signed amount per reference (positive = money into the bank) and compared.
 * Findings are reported, never auto-fixed: a human decides whether the bank or the ledger is wrong.
 */
@Service
public class ReconciliationService {
    public record BankLine(String reference, long amount) {}   // signed minor units; +in, -out

    public record Item(String category, String reference, Long ledgerAmount, Long bankAmount, String note) {}

    public record Result(UUID id, boolean replayed, Instant from, Instant to, int bankLines, int ledgerRefs, int matched, List<Item> items) {}

    private final JdbcClient jdbc;
    private final TxRunner tx;

    public ReconciliationService(JdbcClient jdbc, TxRunner tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public Result reconcile(Instant from, Instant to, List<BankLine> statement) {
        if (from == null || to == null || !to.isAfter(from)) {
            throw new InvalidEntryException("period_to must be after period_from");
        }
        if (statement == null || statement.size() > 100_000) {
            throw new InvalidEntryException("statement must have at most 100000 lines");
        }
        for (BankLine l : statement) {
            if (l == null || l.reference() == null || l.reference().isBlank() || l.amount() == 0) {
                throw new InvalidEntryException("every statement line needs a reference and a non-zero amount");
            }
        }
        String hash = hash(from, to, statement);
        return tx.run(() -> {
            // Serialise runs of the same statement; the unique hash is the real guard.
            boolean fresh = jdbc.sql("""
                    INSERT INTO reconciliations (statement_hash, period_from, period_to, bank_lines, ledger_refs, matched)
                    VALUES (:h, :f, :t, 0, 0, 0) ON CONFLICT (statement_hash) DO NOTHING""")
                    .param("h", hash).param("f", java.sql.Timestamp.from(from)).param("t", java.sql.Timestamp.from(to)).update() == 1;
            UUID id = jdbc.sql("SELECT id FROM reconciliations WHERE statement_hash = :h").param("h", hash).query(UUID.class).single();
            if (!fresh) {
                return load(id, true);
            }
            Map<String, Long> ledger = ledgerSide(from, to);
            Map<String, Long> bank = new LinkedHashMap<>();
            Map<String, Integer> seen = new HashMap<>();
            List<Item> items = new ArrayList<>();
            for (BankLine l : statement) {
                int n = seen.merge(l.reference(), 1, Integer::sum);
                if (n == 2) {
                    items.add(new Item("DUPLICATE_IN_BANK", l.reference(), ledger.get(l.reference()), l.amount(), "the bank lists this reference more than once"));
                }
                if (n == 1) {
                    bank.put(l.reference(), l.amount());     // a duplicate is reported, and only the first line is compared
                }
            }
            int matched = 0;
            for (var e : bank.entrySet()) {
                Long lv = ledger.get(e.getKey());
                if (lv == null) {
                    items.add(new Item("MISSING_IN_LEDGER", e.getKey(), null, e.getValue(), "bank has it, ledger does not"));
                } else if (!lv.equals(e.getValue())) {
                    items.add(new Item("AMOUNT_MISMATCH", e.getKey(), lv, e.getValue(), "ledger " + lv + " vs bank " + e.getValue()));
                } else {
                    matched++;
                }
            }
            for (var e : ledger.entrySet()) {
                if (!bank.containsKey(e.getKey())) {
                    items.add(new Item("MISSING_IN_BANK", e.getKey(), e.getValue(), null, "ledger has it, bank does not"));
                }
            }
            for (Item i : items) {
                jdbc.sql("""
                        INSERT INTO reconciliation_items (reconciliation_id, category, reference, ledger_amount, bank_amount, note)
                        VALUES (:r, :c, :ref, :l, :b, :n)""")
                        .param("r", id).param("c", i.category()).param("ref", i.reference()).param("l", i.ledgerAmount()).param("b", i.bankAmount()).param("n", i.note()).update();
            }
            jdbc.sql("UPDATE reconciliations SET bank_lines = :b, ledger_refs = :l, matched = :m WHERE id = :i")
                    .param("b", statement.size()).param("l", ledger.size()).param("m", matched).param("i", id).update();
            return load(id, false);
        });
    }

    /** Signed net per reference for entries touching bank:settlement in [from, to). Entries without a reference use their id. */
    private Map<String, Long> ledgerSide(Instant from, Instant to) {
        Map<String, Long> m = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT COALESCE(e.reference, 'entry:' || e.public_id) AS ref,
                       SUM(CASE l.side WHEN 'D' THEN l.amount ELSE -l.amount END) AS net
                  FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id JOIN accounts a ON a.id = l.account_id
                 WHERE a.code = 'bank:settlement' AND e.posted_at >= :f AND e.posted_at < :t
                 GROUP BY 1 HAVING SUM(CASE l.side WHEN 'D' THEN l.amount ELSE -l.amount END) <> 0 ORDER BY 1""")
                .param("f", java.sql.Timestamp.from(from)).param("t", java.sql.Timestamp.from(to))
                .query((rs, n) -> { m.put(rs.getString(1), rs.getLong(2)); return null; }).list();
        return m;
    }

    public Result load(UUID id, boolean replayed) {
        var head = jdbc.sql("SELECT period_from, period_to, bank_lines, ledger_refs, matched FROM reconciliations WHERE id = :i").param("i", id)
                .query((rs, n) -> new Object[]{rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant(), rs.getInt(3), rs.getInt(4), rs.getInt(5)})
                .optional().orElseThrow(() -> new NotFoundException("no reconciliation " + id));
        List<Item> items = jdbc.sql("SELECT category, reference, ledger_amount, bank_amount, note FROM reconciliation_items WHERE reconciliation_id = :i ORDER BY id")
                .param("i", id).query((rs, n) -> new Item(rs.getString(1), rs.getString(2), (Long) rs.getObject(3), (Long) rs.getObject(4), rs.getString(5))).list();
        return new Result(id, replayed, (Instant) head[0], (Instant) head[1], (Integer) head[2], (Integer) head[3], (Integer) head[4], items);
    }

    static String hash(Instant from, Instant to, List<BankLine> lines) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update((from.toEpochMilli() + "|" + to.toEpochMilli() + "\n").getBytes(StandardCharsets.UTF_8));
            for (BankLine l : lines) {
                d.update((l.reference().length() + ":" + l.reference() + "|" + l.amount() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(d.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
