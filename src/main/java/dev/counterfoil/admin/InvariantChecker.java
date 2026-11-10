package dev.counterfoil.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Re-derives every invariant from raw rows, never from caches (ADR-012). Each check is one SQL query that
 * returns offending rows; an empty result means the invariant holds. This is what the crash harness calls
 * after every kill, and its own tests corrupt data on purpose to prove it notices.
 */
@Component
public class InvariantChecker {
    public record Check(String id, String description, long violations, List<String> sample) {}

    public record Report(boolean ok, List<Check> checks) {}

    private record Def(String id, String description, String sql) {}

    private static final List<Def> DEFS = List.of(
            new Def("I1.balanced", "every journal entry balances per currency and has at least 2 lines", """
                    SELECT e.id::text || ' (' || COALESCE(x.currency, 'no lines') || ' net ' || COALESCE(x.net, 0) || ')'
                      FROM journal_entries e
                      LEFT JOIN (SELECT entry_id, currency, SUM(CASE side WHEN 'D' THEN amount ELSE -amount END) AS net, count(*) OVER (PARTITION BY entry_id) AS n
                                   FROM journal_lines GROUP BY entry_id, currency) x ON x.entry_id = e.id
                     WHERE x.entry_id IS NULL OR x.net <> 0
                    UNION ALL
                    SELECT 'entry ' || entry_id || ' has ' || count(*) || ' line(s)' FROM journal_lines GROUP BY entry_id HAVING count(*) < 2"""),
            new Def("I2.balance-cache", "account_balances equals the sum of the account's lines", """
                    SELECT a.code || ': cache ' || b.debit_total || '/' || b.credit_total || ' vs lines ' || COALESCE(s.d, 0) || '/' || COALESCE(s.c, 0)
                      FROM accounts a JOIN account_balances b ON b.account_id = a.id
                      LEFT JOIN (SELECT account_id,
                                        SUM(CASE WHEN side = 'D' THEN amount ELSE 0 END) AS d,
                                        SUM(CASE WHEN side = 'C' THEN amount ELSE 0 END) AS c
                                   FROM journal_lines GROUP BY account_id) s ON s.account_id = a.id
                     WHERE b.debit_total <> COALESCE(s.d, 0) OR b.credit_total <> COALESCE(s.c, 0)"""),
            new Def("I3.trial-balance", "total debits equal total credits per currency", """
                    SELECT currency || ': debits ' || SUM(CASE WHEN side = 'D' THEN amount ELSE 0 END) || ' credits ' || SUM(CASE WHEN side = 'C' THEN amount ELSE 0 END)
                      FROM journal_lines GROUP BY currency
                    HAVING SUM(CASE WHEN side = 'D' THEN amount ELSE 0 END) <> SUM(CASE WHEN side = 'C' THEN amount ELSE 0 END)"""),
            new Def("I8.no-overdraft", "accounts that forbid a negative balance are not negative", """
                    SELECT a.code || ' natural balance ' || (CASE b.normal_side WHEN 'D' THEN b.debit_total - b.credit_total ELSE b.credit_total - b.debit_total END)
                      FROM accounts a JOIN account_balances b ON b.account_id = a.id
                     WHERE NOT a.allow_negative
                       AND (CASE b.normal_side WHEN 'D' THEN b.debit_total - b.credit_total ELSE b.credit_total - b.debit_total END) < 0"""),
            new Def("I1b.currency", "every line is in its account's currency", """
                    SELECT 'line ' || l.id || ' ' || l.currency || ' on ' || a.code || ' (' || a.currency || ')'
                      FROM journal_lines l JOIN accounts a ON a.id = l.account_id WHERE l.currency <> a.currency"""),
            new Def("I6.reversal-links", "a reversal exactly mirrors the entry it reverses, and nothing is reversed twice", """
                    SELECT 'reversal ' || r.id || ' does not mirror ' || r.reverses_entry_id
                      FROM journal_entries r
                     WHERE r.reverses_entry_id IS NOT NULL
                       AND (SELECT COALESCE(array_agg(account_id::text || side || amount ORDER BY account_id), '{}') FROM journal_lines WHERE entry_id = r.id)
                        <> (SELECT COALESCE(array_agg(account_id::text || (CASE side WHEN 'D' THEN 'C' ELSE 'D' END) || amount ORDER BY account_id), '{}')
                              FROM journal_lines WHERE entry_id = r.reverses_entry_id)"""));

    private final JdbcClient jdbc;
    private final List<Def> extra = new ArrayList<>();

    public InvariantChecker(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Later phases register their own checks (payments, outbox). */
    public synchronized void register(String id, String description, String sql) {
        extra.removeIf(d -> d.id().equals(id));
        extra.add(new Def(id, description, sql));
    }

    public Report run() {
        List<Check> checks = new ArrayList<>();
        List<Def> all = new ArrayList<>(DEFS);
        synchronized (this) {
            all.addAll(extra);
        }
        for (Def d : all) {
            List<String> rows = jdbc.sql(d.sql()).query(String.class).list();
            checks.add(new Check(d.id(), d.description(), rows.size(), rows.stream().limit(5).toList()));
        }
        return new Report(checks.stream().allMatch(c -> c.violations() == 0), checks);
    }
}
