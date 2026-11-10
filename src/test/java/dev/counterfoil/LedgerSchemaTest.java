package dev.counterfoil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The rules live in the database: these tests talk raw SQL and bypass the service on purpose (I1, I6, I8). */
class LedgerSchemaTest {
    static TestEnv env;

    @BeforeAll
    static void setUp() {
        env = new TestEnv("schema", 8);
    }

    static long account(String code, String type, String cur, boolean allowNegative) throws SQLException {
        try (Connection c = env.ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO accounts (code, name, type, currency, allow_negative) VALUES (?, ?, ?, ?, ?) RETURNING id")) {
            ps.setString(1, code + "-" + System.nanoTime());
            ps.setString(2, code);
            ps.setString(3, type);
            ps.setString(4, cur);
            ps.setBoolean(5, allowNegative);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    static long entry(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("INSERT INTO journal_entries (kind) VALUES ('MANUAL') RETURNING id")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static void line(Connection c, long entry, int no, long acct, String side, long amt, String cur) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO journal_lines (entry_id, line_no, account_id, side, amount, currency) VALUES (?,?,?,?,?,?)")) {
            ps.setLong(1, entry); ps.setInt(2, no); ps.setLong(3, acct); ps.setString(4, side); ps.setLong(5, amt); ps.setString(6, cur);
            ps.executeUpdate();
        }
    }

    static String sqlState(ThrowingRunnable r) {
        try {
            r.run();
        } catch (SQLException e) {
            return e.getSQLState();
        }
        return "no error";
    }

    interface ThrowingRunnable { void run() throws SQLException; }

    @Test
    void unbalancedEntryIsRejectedAtCommit() throws Exception {
        long a = account("a", "ASSET", "INR", true), b = account("b", "LIABILITY", "INR", true);
        try (Connection c = env.ds.getConnection()) {
            c.setAutoCommit(false);
            long e = entry(c);
            line(c, e, 1, a, "D", 100, "INR");
            line(c, e, 2, b, "C", 90, "INR");   // statements succeed: the check is deferred to COMMIT
            assertThat(sqlState(c::commit)).isEqualTo("23514");
        }
    }

    @Test
    void entryNeedsAtLeastTwoLinesAndSomeLines() throws Exception {
        long a = account("a", "ASSET", "INR", true);
        try (Connection c = env.ds.getConnection()) {
            c.setAutoCommit(false);
            long e = entry(c);
            line(c, e, 1, a, "D", 100, "INR");
            assertThat(sqlState(c::commit)).isEqualTo("23514");
        }
        try (Connection c = env.ds.getConnection()) {
            c.setAutoCommit(false);
            entry(c);
            assertThat(sqlState(c::commit)).as("an entry with no lines").isEqualTo("23514");
        }
    }

    @Test
    void balancedEntryCommitsAndBalancesFollow() throws Exception {
        long a = account("cash", "ASSET", "INR", true), b = account("cap", "EQUITY", "INR", true);
        try (Connection c = env.ds.getConnection()) {
            c.setAutoCommit(false);
            long e = entry(c);
            line(c, e, 1, a, "D", 250, "INR");
            line(c, e, 2, b, "C", 250, "INR");
            c.commit();
        }
        assertThat(env.jdbc.sql("SELECT debit_total FROM account_balances WHERE account_id = :a").param("a", a).query(Long.class).single()).isEqualTo(250);
        assertThat(env.jdbc.sql("SELECT credit_total FROM account_balances WHERE account_id = :a").param("a", b).query(Long.class).single()).isEqualTo(250);
        env.assertInvariants();
    }

    @Test
    void multiCurrencyEntryBalancesPerCurrency() throws Exception {
        long inr1 = account("i1", "ASSET", "INR", true), inr2 = account("i2", "EQUITY", "INR", true);
        long usd1 = account("u1", "ASSET", "USD", true), usd2 = account("u2", "EQUITY", "USD", true);
        try (Connection c = env.ds.getConnection()) {
            c.setAutoCommit(false);
            long e = entry(c);
            line(c, e, 1, inr1, "D", 100, "INR"); line(c, e, 2, inr2, "C", 100, "INR");
            line(c, e, 3, usd1, "D", 7, "USD"); line(c, e, 4, usd2, "C", 7, "USD");
            c.commit();
        }
        try (Connection c = env.ds.getConnection()) {      // 100 INR vs 7 USD must NOT be accepted as "balanced"
            c.setAutoCommit(false);
            long e = entry(c);
            line(c, e, 1, inr1, "D", 100, "INR"); line(c, e, 2, usd2, "C", 100, "USD");
            assertThat(sqlState(c::commit)).isEqualTo("23514");
        }
    }

    @Test
    void lineCurrencyMustMatchAccountCurrency() throws Exception {
        long usd = account("usd", "ASSET", "USD", true);
        try (Connection c = env.ds.getConnection()) {
            c.setAutoCommit(false);
            long e = entry(c);
            assertThat(sqlState(() -> line(c, e, 1, usd, "D", 5, "INR"))).isEqualTo("23514");
        }
    }

    @Test
    void ledgerIsAppendOnly() throws Exception {
        long a = account("a", "ASSET", "INR", true), b = account("b", "EQUITY", "INR", true);
        long eid;
        try (Connection c = env.ds.getConnection()) {
            c.setAutoCommit(false);
            eid = entry(c);
            line(c, eid, 1, a, "D", 10, "INR");
            line(c, eid, 2, b, "C", 10, "INR");
            c.commit();
        }
        try (Connection c = env.ds.getConnection(); Statement s = c.createStatement()) {
            for (String sql : new String[]{
                    "UPDATE journal_lines SET amount = 999 WHERE entry_id = " + eid,
                    "DELETE FROM journal_lines WHERE entry_id = " + eid,
                    "UPDATE journal_entries SET description = 'x' WHERE id = " + eid,
                    "DELETE FROM journal_entries WHERE id = " + eid,
                    "TRUNCATE journal_lines",
                    "TRUNCATE journal_entries CASCADE",
                    "UPDATE accounts SET currency = 'USD' WHERE id = " + a,
                    "DELETE FROM accounts WHERE id = " + a}) {
                assertThat(sqlState(() -> s.execute(sql))).as(sql).isEqualTo("55000");
            }
        }
    }

    @Test
    void noOverdraftIsEnforcedByTheDatabase() throws Exception {
        long wallet = account("wallet", "LIABILITY", "INR", false), bank = account("bank", "ASSET", "INR", true);
        try (Connection c = env.ds.getConnection()) {      // fund: Dr bank 100 / Cr wallet 100
            c.setAutoCommit(false);
            long e = entry(c);
            line(c, e, 1, bank, "D", 100, "INR"); line(c, e, 2, wallet, "C", 100, "INR");
            c.commit();
        }
        try (Connection c = env.ds.getConnection()) {      // withdraw 150 from a wallet holding 100
            c.setAutoCommit(false);
            long e = entry(c);
            // the CHECK fires at statement time, on the balance row, under its lock
            assertThat(sqlState(() -> line(c, e, 1, wallet, "D", 150, "INR"))).isEqualTo("23514");
        }
    }

    @Test
    void twoConcurrentDebitsCannotBothSpendTheSameMoney() throws Exception {
        long wallet = account("w", "LIABILITY", "INR", false), bank = account("bk", "ASSET", "INR", true);
        try (Connection c = env.ds.getConnection()) {
            c.setAutoCommit(false);
            long e = entry(c);
            line(c, e, 1, bank, "D", 100, "INR"); line(c, e, 2, wallet, "C", 100, "INR");
            c.commit();
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<Boolean> spend60 = () -> {
            try (Connection c = env.ds.getConnection()) {
                c.setAutoCommit(false);
                go.await();
                long e = entry(c);
                line(c, e, 1, wallet, "D", 60, "INR");   // takes the row lock on the balance; CHECK runs under it
                Thread.sleep(150);                       // hold the lock so the other one really races
                line(c, e, 2, bank, "C", 60, "INR");
                c.commit();
                return true;
            } catch (SQLException ex) {
                return false;
            }
        };
        var f1 = pool.submit(spend60);
        var f2 = pool.submit(spend60);
        go.countDown();
        int ok = (f1.get() ? 1 : 0) + (f2.get() ? 1 : 0);
        pool.shutdown();
        assertThat(ok).as("exactly one of two 60-spends from a balance of 100 may succeed").isEqualTo(1);
        assertThat(env.jdbc.sql("SELECT credit_total - debit_total FROM account_balances WHERE account_id = :a").param("a", wallet).query(Long.class).single()).isEqualTo(40);
    }

    @Test
    void anEntryCanBeReversedAtMostOnce() throws Exception {
        long a = account("a", "ASSET", "INR", true), b = account("b", "EQUITY", "INR", true);
        long orig;
        try (Connection c = env.ds.getConnection()) {
            c.setAutoCommit(false);
            orig = entry(c);
            line(c, orig, 1, a, "D", 10, "INR"); line(c, orig, 2, b, "C", 10, "INR");
            c.commit();
        }
        for (int i = 0; i < 2; i++) {
            final int n = i;
            String st = sqlState(() -> {
                try (Connection c = env.ds.getConnection()) {
                    c.setAutoCommit(false);
                    long r;
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO journal_entries (kind, reverses_entry_id) VALUES ('REVERSAL', ?) RETURNING id")) {
                        ps.setLong(1, orig);
                        try (ResultSet rs = ps.executeQuery()) { rs.next(); r = rs.getLong(1); }
                    }
                    line(c, r, 1, a, "C", 10, "INR"); line(c, r, 2, b, "D", 10, "INR");
                    c.commit();
                }
            });
            assertThat(st).as("reversal attempt " + n).isEqualTo(n == 0 ? "no error" : "23505");
        }
    }
}
