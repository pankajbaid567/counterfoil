package dev.counterfoil;

import static org.assertj.core.api.Assertions.assertThat;

import dev.counterfoil.admin.InvariantChecker;
import dev.counterfoil.ledger.*;
import dev.counterfoil.ledger.Records.*;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The checker must notice real corruption, so each test damages data on purpose (triggers disabled in that session only). */
class InvariantCheckerTest {

    private TestEnv seeded() {
        TestEnv env = new TestEnv("inv", 4);
        env.ledger.createAccount("cash", null, AccountType.ASSET, "INR", true);
        env.ledger.createAccount("cap", null, AccountType.EQUITY, "INR", true);
        env.ledger.createAccount("wallet", null, AccountType.LIABILITY, "INR", false);
        env.ledger.post(new PostRequest("seed", null, List.of(new LineRequest("cash", Side.D, 100), new LineRequest("cap", Side.C, 100))));
        return env;
    }

    private void corrupt(TestEnv env, String... sql) throws Exception {
        try (Connection c = env.ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("SET session_replication_role = replica");       // bypass triggers: only a superuser can, and only here
            for (String q : sql) s.execute(q);
        }
    }

    private InvariantChecker.Check check(TestEnv env, String id) {
        return env.invariants.run().checks().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void cleanDataPasses() {
        TestEnv env = seeded();
        assertThat(env.invariants.run().ok()).isTrue();
        assertThat(env.invariants.run().checks()).allSatisfy(c -> assertThat(c.violations()).isZero());
    }

    @Test
    void detectsAnUnbalancedEntry() throws Exception {
        TestEnv env = seeded();
        corrupt(env, "INSERT INTO journal_entries (id, kind) VALUES (9001, 'MANUAL')",
                "INSERT INTO journal_lines (entry_id, line_no, account_id, side, amount, currency) VALUES (9001, 1, (SELECT id FROM accounts WHERE code='cash'), 'D', 50, 'INR')",
                "INSERT INTO journal_lines (entry_id, line_no, account_id, side, amount, currency) VALUES (9001, 2, (SELECT id FROM accounts WHERE code='cap'), 'C', 40, 'INR')");
        assertThat(check(env, "I1.balanced").violations()).isPositive();
        assertThat(check(env, "I3.trial-balance").violations()).isPositive();
        assertThat(env.invariants.run().ok()).isFalse();
    }

    @Test
    void replicaRoleCannotBypassTheNoOverdraftConstraint() {
        TestEnv env = seeded();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> corrupt(env,
                "UPDATE account_balances SET debit_total = 5 WHERE account_id = (SELECT id FROM accounts WHERE code='wallet')"))
                .hasMessageContaining("no_overdraft");
    }

    @Test
    void detectsADriftedBalanceCache() throws Exception {
        TestEnv env = seeded();
        corrupt(env, "UPDATE account_balances SET debit_total = debit_total + 7 WHERE account_id = (SELECT id FROM accounts WHERE code='cash')");
        var c = check(env, "I2.balance-cache");
        assertThat(c.violations()).isEqualTo(1);
        assertThat(c.sample().get(0)).contains("cash");
    }

    @Test
    void detectsAnOverdraftedGuardedAccount() throws Exception {
        TestEnv env = seeded();
        // disabling triggers does not bypass CHECK constraints, so the constraint itself has to be dropped to damage the data
        corrupt(env, "ALTER TABLE account_balances DROP CONSTRAINT no_overdraft",
                "UPDATE account_balances SET debit_total = 5 WHERE account_id = (SELECT id FROM accounts WHERE code='wallet')");
        assertThat(check(env, "I8.no-overdraft").violations()).isEqualTo(1);
    }

    @Test
    void detectsALineInTheWrongCurrency() throws Exception {
        TestEnv env = seeded();
        corrupt(env, "UPDATE journal_lines SET currency = 'USD' WHERE id = (SELECT min(id) FROM journal_lines)");
        assertThat(check(env, "I1b.currency").violations()).isEqualTo(1);
    }

    @Test
    void detectsAReversalThatDoesNotMirrorItsOriginal() throws Exception {
        TestEnv env = seeded();
        var e = env.ledger.post(new PostRequest("orig", null, List.of(new LineRequest("cash", Side.D, 10), new LineRequest("cap", Side.C, 10))));
        env.ledger.reverse(e.publicId(), null);
        assertThat(check(env, "I6.reversal-links").violations()).isZero();
        corrupt(env, "UPDATE journal_lines SET amount = amount + 1 WHERE entry_id = (SELECT id FROM journal_entries WHERE kind = 'REVERSAL')");
        assertThat(check(env, "I6.reversal-links").violations()).isPositive();
    }
}
