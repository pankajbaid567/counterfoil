package dev.counterfoil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.counterfoil.ledger.*;
import dev.counterfoil.ledger.Records.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LedgerServiceTest {
    static TestEnv env;
    static int seq;

    @BeforeAll
    static void setUp() {
        env = new TestEnv("service", 16);
    }

    static String code(String p) {
        return p + "-" + (++seq);
    }

    static LineRequest d(String a, long n) { return new LineRequest(a, Side.D, n); }
    static LineRequest c(String a, long n) { return new LineRequest(a, Side.C, n); }

    @Test
    void postsAnEntryAndUpdatesBalances() {
        String cash = code("cash"), cap = code("capital");
        env.ledger.createAccount(cash, "Cash", AccountType.ASSET, "INR", true);
        env.ledger.createAccount(cap, "Capital", AccountType.EQUITY, "INR", true);
        EntryView e = env.ledger.post(new PostRequest("ref-1", "seed", List.of(d(cash, 500), c(cap, 500))));
        assertThat(e.lines()).hasSize(2);
        assertThat(env.ledger.balance(cash).natural()).isEqualTo(500);     // asset: debit - credit
        assertThat(env.ledger.balance(cap).natural()).isEqualTo(500);      // equity: credit - debit
        assertThat(env.ledger.findEntry(e.publicId())).isPresent();
        env.assertInvariants();
    }

    @Test
    void mergesLinesOfTheSameAccountAndSide() {
        String a = code("a"), b = code("b");
        env.ledger.createAccount(a, null, AccountType.ASSET, "INR", true);
        env.ledger.createAccount(b, null, AccountType.EQUITY, "INR", true);
        EntryView e = env.ledger.post(new PostRequest(null, null, List.of(d(a, 30), d(a, 70), c(b, 100))));
        assertThat(e.lines()).hasSize(2);
        assertThat(env.ledger.balance(a).debitTotal()).isEqualTo(100);
    }

    @Test
    void rejectsBadEntriesWithStableCodes() {
        String a = code("a"), b = code("b");
        env.ledger.createAccount(a, null, AccountType.ASSET, "INR", true);
        env.ledger.createAccount(b, null, AccountType.EQUITY, "INR", true);
        env.ledger.createAccount(code("usd"), null, AccountType.ASSET, "USD", true);
        assertThatThrownBy(() -> env.ledger.post(new PostRequest(null, null, List.of(d(a, 10)))))
                .isInstanceOf(InvalidEntryException.class).hasMessageContaining("at least 2 lines");
        assertThatThrownBy(() -> env.ledger.post(new PostRequest(null, null, List.of(d(a, 10), c(b, 9)))))
                .isInstanceOf(InvalidEntryException.class).hasMessageContaining("unbalanced");
        assertThatThrownBy(() -> env.ledger.post(new PostRequest(null, null, List.of(d(a, 10), c(a, 10)))))
                .isInstanceOf(InvalidEntryException.class).hasMessageContaining("both sides");
        assertThatThrownBy(() -> env.ledger.post(new PostRequest(null, null, List.of(d(a, 0), c(b, 0)))))
                .isInstanceOf(InvalidEntryException.class);
        assertThatThrownBy(() -> env.ledger.post(new PostRequest(null, null, List.of(d(a, -5), c(b, -5)))))
                .isInstanceOf(InvalidEntryException.class);
        assertThatThrownBy(() -> env.ledger.post(new PostRequest(null, null, List.of(d(a, Long.MAX_VALUE), c(b, Long.MAX_VALUE)))))
                .isInstanceOf(InvalidEntryException.class);
        assertThatThrownBy(() -> env.ledger.post(new PostRequest(null, null, List.of(d(a, 10), c("nope-" + seq, 10)))))
                .isInstanceOf(NotFoundException.class);
        env.assertInvariants();
    }

    @Test
    void crossCurrencyEntryMustBalanceInEachCurrency() {
        String inr = code("inr"), inr2 = code("inr2"), usd = code("usd");
        env.ledger.createAccount(inr, null, AccountType.ASSET, "INR", true);
        env.ledger.createAccount(inr2, null, AccountType.EQUITY, "INR", true);
        env.ledger.createAccount(usd, null, AccountType.EQUITY, "USD", true);
        assertThatThrownBy(() -> env.ledger.post(new PostRequest(null, null, List.of(d(inr, 100), c(usd, 100)))))
                .isInstanceOf(InvalidEntryException.class).hasMessageContaining("unbalanced in");
        assertThat(env.ledger.post(new PostRequest(null, null, List.of(d(inr, 100), c(inr2, 100)))).lines()).hasSize(2);
    }

    @Test
    void overdraftIsReportedAsInsufficientFunds() {
        String wallet = code("wallet"), bank = code("bank");
        env.ledger.createAccount(wallet, null, AccountType.LIABILITY, "INR", false);
        env.ledger.createAccount(bank, null, AccountType.ASSET, "INR", true);
        env.ledger.post(new PostRequest(null, null, List.of(d(bank, 100), c(wallet, 100))));
        assertThatThrownBy(() -> env.ledger.post(new PostRequest(null, null, List.of(d(wallet, 101), c(bank, 101)))))
                .isInstanceOf(InsufficientFundsException.class);
        env.ledger.post(new PostRequest(null, null, List.of(d(wallet, 100), c(bank, 100))));       // exactly zero is fine
        assertThat(env.ledger.balance(wallet).natural()).isZero();
        env.assertInvariants();
    }

    @Test
    void reversalMirrorsTheEntryAndWorksOnce() {
        String a = code("a"), b = code("b");
        env.ledger.createAccount(a, null, AccountType.ASSET, "INR", true);
        env.ledger.createAccount(b, null, AccountType.EQUITY, "INR", true);
        EntryView e = env.ledger.post(new PostRequest("r", "x", List.of(d(a, 40), c(b, 40))));
        EntryView r = env.ledger.reverse(e.publicId(), null);
        assertThat(r.kind()).isEqualTo(Kind.REVERSAL);
        assertThat(r.reversesEntryId()).isEqualTo(e.id());
        assertThat(env.ledger.balance(a).natural()).isZero();
        assertThatThrownBy(() -> env.ledger.reverse(e.publicId(), null)).isInstanceOf(AlreadyReversedException.class);
        assertThatThrownBy(() -> env.ledger.reverse(r.publicId(), null)).isInstanceOf(InvalidEntryException.class).hasMessageContaining("reversal");
        assertThatThrownBy(() -> env.ledger.reverse(UUID.randomUUID(), null)).isInstanceOf(NotFoundException.class);
        env.assertInvariants();
    }

    @Test
    void reversingTheSameEntryConcurrentlyYieldsExactlyOneReversal() throws Exception {
        String a = code("a"), b = code("b");
        env.ledger.createAccount(a, null, AccountType.ASSET, "INR", true);
        env.ledger.createAccount(b, null, AccountType.EQUITY, "INR", true);
        EntryView e = env.ledger.post(new PostRequest(null, null, List.of(d(a, 5), c(b, 5))));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Boolean>> fs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            fs.add(pool.submit(() -> {
                try { env.ledger.reverse(e.publicId(), null); return true; } catch (AlreadyReversedException x) { return false; }
            }));
        }
        long wins = 0;
        for (var f : fs) { if (f.get()) wins++; }
        pool.shutdown();
        assertThat(wins).isEqualTo(1);
        env.assertInvariants();
    }

    @Test
    void statementPagesInOrderAndTrialBalanceBalances() {
        String a = code("a"), b = code("b");
        env.ledger.createAccount(a, null, AccountType.ASSET, "INR", true);
        env.ledger.createAccount(b, null, AccountType.EQUITY, "INR", true);
        for (int i = 1; i <= 7; i++) {
            env.ledger.post(new PostRequest("p" + i, null, List.of(d(a, i), c(b, i))));
        }
        List<StatementLine> page1 = env.ledger.statement(a, 0, 3);
        List<StatementLine> page2 = env.ledger.statement(a, page1.get(2).lineId(), 100);
        assertThat(page1).hasSize(3);
        assertThat(page2).hasSize(4);
        assertThat(page1.get(0).reference()).isEqualTo("p1");
        assertThat(page2.get(3).amount()).isEqualTo(7);
        assertThatThrownBy(() -> env.ledger.statement("missing-" + seq, 0, 10)).isInstanceOf(NotFoundException.class);
        TrialBalance tb = env.ledger.trialBalance();
        assertThat(tb.balanced()).isTrue();
        assertThat(tb.totals()).allSatisfy(t -> assertThat(t.debits()).isEqualTo(t.credits()));
    }

    @Test
    void accountCreationIsUniqueAndEnsureAccountIsRaceSafe() throws Exception {
        String code = code("dup");
        env.ledger.createAccount(code, null, AccountType.ASSET, "INR", true);
        assertThatThrownBy(() -> env.ledger.createAccount(code, null, AccountType.ASSET, "INR", true)).isInstanceOf(DuplicateAccountException.class);
        assertThatThrownBy(() -> env.ledger.createAccount("Bad Code!", null, AccountType.ASSET, "INR", true)).isInstanceOf(InvalidEntryException.class);
        assertThatThrownBy(() -> env.ledger.createAccount(code("x"), null, AccountType.ASSET, "inr", true)).isInstanceOf(InvalidEntryException.class);
        String fresh = code("race");
        ExecutorService pool = Executors.newFixedThreadPool(10);
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            fs.add(pool.submit(() -> ids.add(env.ledger.ensureAccount(fresh, null, AccountType.ASSET, "INR", true).id())));
        }
        for (var f : fs) { try { f.get(); } catch (Exception e) { throw new AssertionError(e); } }
        pool.shutdown();
        assertThat(ids).hasSize(1);
    }
}
