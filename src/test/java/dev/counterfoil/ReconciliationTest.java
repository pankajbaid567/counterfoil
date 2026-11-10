package dev.counterfoil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.counterfoil.ledger.InvalidEntryException;
import dev.counterfoil.ledger.Records.*;
import dev.counterfoil.ledger.Side;
import dev.counterfoil.payments.ReconciliationService.BankLine;
import dev.counterfoil.payments.ReconciliationService.Result;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReconciliationTest {
    TestEnv env;
    Instant from = Instant.now().minusSeconds(3600), to = Instant.now().plusSeconds(3600);

    @BeforeEach
    void setUp() { env = new TestEnv("recon", 8); }

    void bankIn(String ref, long amt) {
        env.ledger.post(new PostRequest(Kind.PSP_SETTLEMENT, ref, "in", List.of(new LineRequest("bank:settlement", Side.D, amt), new LineRequest("psp_clearing", Side.C, amt))));
    }

    void bankOut(String ref, long amt) {
        env.ledger.post(new PostRequest(Kind.PAYOUT, ref, "out", List.of(new LineRequest("merchant:m:payable", Side.D, amt), new LineRequest("bank:settlement", Side.C, amt))));
    }

    long count(Result r, String cat) { return r.items().stream().filter(i -> i.category().equals(cat)).count(); }

    @BeforeEach
    void seedMerchant() { env.ledger.ensureAccount("merchant:m:payable", "m", dev.counterfoil.ledger.AccountType.LIABILITY, "INR", true); }

    @Test
    void identicalSidesMatchWithNoFindings() {
        bankIn("s1", 500); bankIn("s2", 700); bankOut("p1", 300);
        Result r = env.reconciliation.reconcile(from, to, List.of(new BankLine("s1", 500), new BankLine("s2", 700), new BankLine("p1", -300)));
        assertThat(r.matched()).isEqualTo(3);
        assertThat(r.items()).isEmpty();
    }

    @Test
    void everyKindOfDifferenceIsReportedOnce() {
        bankIn("ok", 100); bankIn("only-ledger", 200); bankIn("diff", 300);
        Result r = env.reconciliation.reconcile(from, to, List.of(new BankLine("ok", 100), new BankLine("only-bank", 400), new BankLine("diff", 299),
                new BankLine("dup", 50), new BankLine("dup", 50)));
        assertThat(r.matched()).isEqualTo(1);
        assertThat(count(r, "MISSING_IN_BANK")).isEqualTo(1);
        assertThat(count(r, "MISSING_IN_LEDGER")).isEqualTo(2);     // only-bank and dup (not in ledger)
        assertThat(count(r, "AMOUNT_MISMATCH")).isEqualTo(1);
        assertThat(count(r, "DUPLICATE_IN_BANK")).isEqualTo(1);
        assertThat(r.items()).filteredOn(i -> i.category().equals("AMOUNT_MISMATCH")).singleElement()
                .satisfies(i -> { assertThat(i.ledgerAmount()).isEqualTo(300); assertThat(i.bankAmount()).isEqualTo(299); });
    }

    @Test
    void sameStatementTwiceReturnsTheStoredResultAndStoresNothingNew() {
        bankIn("a", 10);
        var stmt = List.of(new BankLine("a", 11));
        Result first = env.reconciliation.reconcile(from, to, stmt);
        Result again = env.reconciliation.reconcile(from, to, stmt);
        assertThat(again.replayed()).isTrue();
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.items()).isEqualTo(first.items());
        assertThat(env.jdbc.sql("SELECT count(*) FROM reconciliation_items").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void concurrentSubmissionsOfOneStatementProduceExactlyOneRun() throws Exception {
        bankIn("a", 10);
        var stmt = List.of(new BankLine("a", 11), new BankLine("b", 5));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<Result>> fs = new ArrayList<>();
        for (int i = 0; i < 6; i++) fs.add(pool.submit(() -> env.reconciliation.reconcile(from, to, stmt)));
        Set<UUID> ids = new HashSet<>(); int fresh = 0;
        for (var f : fs) { Result r = f.get(); ids.add(r.id()); if (!r.replayed()) fresh++; }
        pool.shutdown();
        assertThat(ids).hasSize(1);
        assertThat(fresh).isEqualTo(1);
        assertThat(env.jdbc.sql("SELECT count(*) FROM reconciliation_items").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void entriesOutsideThePeriodAreIgnoredAndAReversalNetsToNothing() {
        bankIn("x", 100);
        var e = env.ledger.post(new PostRequest(Kind.PSP_SETTLEMENT, "y", "y", List.of(new LineRequest("bank:settlement", Side.D, 40), new LineRequest("psp_clearing", Side.C, 40))));
        Result past = env.reconciliation.reconcile(Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3700), List.of());
        assertThat(past.items()).isEmpty();
        env.ledger.reverse(e.publicId(), "y-rev");
        // y (+40) and y-rev (-40) have different references, so each shows separately unless the bank lists them
        Result r = env.reconciliation.reconcile(from, to, List.of(new BankLine("x", 100), new BankLine("y", 40), new BankLine("y-rev", -40)));
        assertThat(r.items()).isEmpty();
        assertThat(r.matched()).isEqualTo(3);
    }

    @Test
    void badInputIsRejected() {
        assertThatThrownBy(() -> env.reconciliation.reconcile(to, from, List.of())).isInstanceOf(InvalidEntryException.class);
        assertThatThrownBy(() -> env.reconciliation.reconcile(from, to, List.of(new BankLine("", 5)))).isInstanceOf(InvalidEntryException.class);
        assertThatThrownBy(() -> env.reconciliation.reconcile(from, to, List.of(new BankLine("a", 0)))).isInstanceOf(InvalidEntryException.class);
    }

    @Test
    void randomisedDifferentialAgainstASimpleModel() {
        Random r = new Random(5);
        Map<String, Long> ledger = new HashMap<>();
        for (int i = 0; i < 60; i++) { long a = 1 + r.nextInt(900); bankIn("r" + i, a); ledger.put("r" + i, a); }
        List<BankLine> stmt = new ArrayList<>();
        int exp_missBank = 0, exp_missLedger = 0, exp_mismatch = 0, exp_match = 0;
        for (int i = 0; i < 60; i++) {
            int dice = r.nextInt(4);
            if (dice == 0) { exp_missBank++; }
            else if (dice == 1) { stmt.add(new BankLine("r" + i, ledger.get("r" + i) + 1)); exp_mismatch++; }
            else { stmt.add(new BankLine("r" + i, ledger.get("r" + i))); exp_match++; }
        }
        for (int i = 0; i < 10; i++) { stmt.add(new BankLine("extra" + i, 7)); exp_missLedger++; }
        Collections.shuffle(stmt, r);
        Result res = env.reconciliation.reconcile(from, to, stmt);
        assertThat(res.matched()).isEqualTo(exp_match);
        assertThat(count(res, "MISSING_IN_BANK")).isEqualTo(exp_missBank);
        assertThat(count(res, "MISSING_IN_LEDGER")).isEqualTo(exp_missLedger);
        assertThat(count(res, "AMOUNT_MISMATCH")).isEqualTo(exp_mismatch);
    }
}
