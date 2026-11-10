package dev.counterfoil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.counterfoil.idempotency.IdempotencyException;
import dev.counterfoil.idempotency.IdempotencyService.Outcome;
import dev.counterfoil.idempotency.RequestHasher;
import dev.counterfoil.ledger.*;
import dev.counterfoil.outbox.OutboxService;
import dev.counterfoil.payments.*;
import dev.counterfoil.payments.Payments.*;
import dev.counterfoil.payments.PspSimulator.Behavior;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PaymentServiceTest {
    TestEnv env;
    PaymentService pay;

    @BeforeEach
    void setUp() {
        env = new TestEnv("pay", 32);
        pay = env.payments;
    }

    // ---- helpers
    static String key() { return "k-" + UUID.randomUUID(); }

    static String h(String s) { return RequestHasher.hash("POST", "/v1/payments", s); }

    PaymentView parse(Outcome o) {
        try {
            return env.mapper.readValue(o.response().body(), PaymentView.class);
        } catch (Exception e) {
            throw new AssertionError(o.response().body(), e);
        }
    }

    UUID created(String merchant, long amount) {
        var o = pay.create("t", key(), h("c"), new CreateRequest(merchant, amount, "INR"));
        return parse(o).id();
    }

    Outcome authorize(UUID id, String key) { return pay.authorize("t", key, h("authorize:" + id), id); }
    Outcome capture(UUID id, String key) { return pay.capture("t", key, h("capture:" + id), id); }
    Outcome refund(UUID id, String key, long amt) { return pay.refund("t", key, h("refund:" + id + ":" + amt), id, new RefundRequest(amt)); }

    UUID captured(String merchant, long amount) {
        UUID id = created(merchant, amount);
        authorize(id, key());
        capture(id, key());
        return id;
    }

    long natural(String account) { return env.ledger.balance(account).natural(); }

    long events(String type, UUID id) {
        return env.jdbc.sql("SELECT count(*) FROM outbox WHERE type = :t AND aggregate_id = :a").param("t", type).param("a", id.toString()).query(Long.class).single();
    }

    long pspCalls(String ref, String kind) {
        return env.jdbc.sql("SELECT count(*) FROM psp_transactions WHERE psp_ref = :r AND kind = :k").param("r", ref).param("k", kind).query(Long.class).single();
    }

    // ---- flows
    @Test
    void happyPathMovesMoneyThroughTheLedger() {
        UUID id = created("m1", 10_000);
        assertThat(parse(authorize(id, key())).status()).isEqualTo(PaymentStatus.AUTHORIZED);
        var v = parse(capture(id, key()));
        assertThat(v.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(v.fee()).isEqualTo(200);                                         // 2% of 10,000
        assertThat(natural("psp_clearing")).isEqualTo(10_000);
        assertThat(natural("merchant:m1:payable")).isEqualTo(9_800);
        assertThat(natural("fees:revenue")).isEqualTo(200);
        for (String type : List.of("payment.created", "payment.authorized", "payment.captured")) {
            assertThat(events(type, id)).as(type).isEqualTo(1);
        }
        env.assertInvariants();
    }

    @Test
    void retryWithTheSameKeyReplaysAndCallsThePspOnce() {
        UUID id = created("m1", 5_000);
        String k = key();
        var first = authorize(id, k);
        var again = authorize(id, k);
        assertThat(first.replayed()).isFalse();
        assertThat(again.replayed()).isTrue();
        assertThat(again.response()).isEqualTo(first.response());
        assertThat(pspCalls("pay_" + id, "AUTHORIZE")).isEqualTo(1);
        assertThat(events("payment.authorized", id)).isEqualTo(1);
        assertThatThrownBy(() -> pay.authorize("t", k, h("something else"), id)).isInstanceOf(IdempotencyException.class);
        env.assertInvariants();
    }

    @Test
    void secondCaptureWithAnotherKeyIsRejectedAndChangesNothing() {
        UUID id = captured("m1", 10_000);
        assertThatThrownBy(() -> capture(id, key())).isInstanceOf(InvalidStateException.class);
        assertThat(natural("merchant:m1:payable")).isEqualTo(9_800);
        env.assertInvariants();
    }

    @Test
    void theDatabaseItselfRefusesASecondCaptureEntry() {
        UUID id = captured("m1", 10_000);
        assertThatThrownBy(() -> env.ledger.postInTx(new Records.PostRequest(Records.Kind.PAYMENT_CAPTURE, "payment:" + id, "dup",
                List.of(new Records.LineRequest("psp_clearing", Side.D, 1), new Records.LineRequest("fees:revenue", Side.C, 1)))))
                .isInstanceOfAny(org.springframework.dao.DataIntegrityViolationException.class, org.springframework.transaction.TransactionException.class,
                        InvalidEntryException.class);
    }

    @Test
    void declinedAuthorizationFailsThePaymentWithoutTouchingTheLedger() {
        env.psp.setPlan(c -> Behavior.DECLINE);
        UUID id = created("m1", 700);
        var v = parse(authorize(id, key()));
        assertThat(v.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(events("payment.failed", id)).isEqualTo(1);
        assertThat(env.jdbc.sql("SELECT count(*) FROM journal_entries").query(Long.class).single()).isZero();
        assertThatThrownBy(() -> authorize(id, key())).isInstanceOf(InvalidStateException.class);
        env.assertInvariants();
    }

    @Test
    void declinedCaptureFailsThePayment() {
        UUID id = created("m1", 700);
        authorize(id, key());
        env.psp.setPlan(c -> c.kind() == PspSimulator.Kind.CAPTURE ? Behavior.DECLINE : Behavior.APPROVE);
        assertThat(parse(capture(id, key())).status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(natural("psp_clearing")).isZero();
        env.assertInvariants();
    }

    @Test
    void voidReleasesAnAuthorization() {
        UUID id = created("m1", 100);
        authorize(id, key());
        assertThat(parse(pay.voidPayment("t", key(), h("v"), id)).status()).isEqualTo(PaymentStatus.VOIDED);
        assertThatThrownBy(() -> capture(id, key())).isInstanceOf(InvalidStateException.class);
        UUID cap = captured("m1", 100);
        assertThatThrownBy(() -> pay.voidPayment("t", key(), h("v2"), cap)).isInstanceOf(InvalidStateException.class);
        env.assertInvariants();
    }

    // ---- the dangerous failures
    @Test
    void pspTimesOutBeforeApplyingThenARetrySucceedsWithOneEffect() {
        var calls = new AtomicInteger();
        env.psp.setPlan(c -> calls.incrementAndGet() == 1 ? Behavior.TIMEOUT_BEFORE : Behavior.APPROVE);
        UUID id = created("m1", 1_000);
        String k = key();
        var first = authorize(id, k);
        assertThat(first.response().status()).isEqualTo(202);
        assertThat(pay.get(id).status()).isEqualTo(PaymentStatus.AUTHORIZING);
        assertThat(pay.pendingOps()).isEqualTo(1);
        var retry = authorize(id, k);                         // the retry helps finish the interrupted operation
        assertThat(retry.response().status()).isEqualTo(200);
        assertThat(pay.get(id).status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(pspCalls("pay_" + id, "AUTHORIZE")).isEqualTo(1);
        assertThat(events("payment.authorized", id)).isEqualTo(1);
        assertThat(pay.pendingOps()).isZero();
        env.assertInvariants();
    }

    @Test
    void pspAppliesTheCaptureThenTimesOutAndTheSweeperCompletesItExactlyOnce() {
        env.psp.setPlan(c -> c.kind() == PspSimulator.Kind.CAPTURE ? Behavior.TIMEOUT_AFTER : Behavior.APPROVE);
        UUID id = created("m1", 10_000);
        authorize(id, key());
        String k = key();
        assertThat(capture(id, k).response().status()).isEqualTo(202);
        assertThat(env.psp.lookup("pay_" + id, PspSimulator.Kind.CAPTURE)).contains(PspSimulator.Outcome.APPROVED);   // the PSP did capture
        assertThat(natural("psp_clearing")).as("we have not recorded it yet").isZero();
        assertThat(pay.recoverStale(0, 10)).isEqualTo(1);
        assertThat(pay.get(id).status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(natural("merchant:m1:payable")).isEqualTo(9_800);
        assertThat(pay.recoverStale(0, 10)).isZero();         // nothing left to do
        var replay = capture(id, k);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.response().status()).isEqualTo(200);
        assertThat(events("payment.captured", id)).isEqualTo(1);
        env.assertInvariants();
    }

    @Test
    void aFailureWhileApplyingTheOutcomeRollsBackEverythingAndRecoveryRedoesIt() {
        // Phase 3 dies after the ledger entry and status were written: the outbox write throws once.
        var failOnce = new AtomicInteger();
        var flaky = new OutboxService(env.jdbc, env.mapper) {
            @Override
            public UUID enqueue(String type, String aggregateId, Object data) {
                if (type.equals("payment.captured") && failOnce.getAndIncrement() == 0) {
                    throw new IllegalStateException("simulated crash while applying the capture");
                }
                return super.enqueue(type, aggregateId, data);
            }
        };
        var svc = new PaymentService(env.jdbc, env.tx, env.ledger, env.idempotency, env.psp, flaky, env.props, env.mapper);
        UUID id = parse(svc.create("t", key(), h("c"), new CreateRequest("m1", 4_000, "INR"))).id();
        svc.authorize("t", key(), h("a"), id);
        String k = key();
        assertThatThrownBy(() -> svc.capture("t", k, h("capture"), id)).isInstanceOf(IllegalStateException.class);
        assertThat(natural("psp_clearing")).as("the ledger entry was rolled back with the failed transaction").isZero();
        assertThat(svc.get(id).status()).isEqualTo(PaymentStatus.CAPTURING);
        assertThat(env.psp.lookup("pay_" + id, PspSimulator.Kind.CAPTURE)).contains(PspSimulator.Outcome.APPROVED);
        assertThat(svc.recoverStale(0, 10)).isEqualTo(1);
        assertThat(svc.get(id).status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(natural("psp_clearing")).isEqualTo(4_000);
        assertThat(events("payment.captured", id)).isEqualTo(1);
        env.assertInvariants();
    }

    @Test
    void ifTheOutboxWriteFailsTheWholeCreateRollsBackAndARetryProducesOnePaymentWithOneEvent() {
        var failOnce = new AtomicInteger();
        var flaky = new OutboxService(env.jdbc, env.mapper) {
            @Override
            public UUID enqueue(String type, String aggregateId, Object data) {
                if (type.equals("payment.created") && failOnce.getAndIncrement() == 0) {
                    throw new IllegalStateException("simulated failure while writing the outbox");
                }
                return super.enqueue(type, aggregateId, data);
            }
        };
        var svc = new PaymentService(env.jdbc, env.tx, env.ledger, env.idempotency, env.psp, flaky, env.props, env.mapper);
        String k = key();
        assertThatThrownBy(() -> svc.create("t", k, h("c"), new CreateRequest("m1", 500, "INR"))).isInstanceOf(IllegalStateException.class);
        assertThat(env.jdbc.sql("SELECT count(*) FROM payments").query(Long.class).single()).as("state change and event commit together or not at all").isZero();
        assertThat(env.jdbc.sql("SELECT count(*) FROM idempotency_keys").query(Long.class).single()).isZero();
        var retry = svc.create("t", k, h("c"), new CreateRequest("m1", 500, "INR"));
        UUID id = parse(retry).id();
        assertThat(retry.replayed()).isFalse();
        assertThat(events("payment.created", id)).isEqualTo(1);
        env.assertInvariants();
    }

    @Test
    void theApiNeverCallsThePspInsideATransaction() {
        env.psp.setPlan(c -> Behavior.APPROVE);
        assertThatThrownBy(() -> env.tx.run(() -> env.psp.call("x", PspSimulator.Kind.AUTHORIZE, 1))).isInstanceOf(IllegalStateException.class);
    }

    // ---- races
    @Test
    void eightThreadsCapturingTheSamePaymentCaptureItExactlyOnce() throws Exception {
        UUID id = created("m1", 10_000);
        authorize(id, key());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> fs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                try { capture(id, key()); return "ok"; } catch (InvalidStateException e) { return "rejected"; }
            }));
        }
        go.countDown();
        long ok = 0;
        for (var f : fs) { if (f.get().equals("ok")) ok++; }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(natural("merchant:m1:payable")).isEqualTo(9_800);
        assertThat(events("payment.captured", id)).isEqualTo(1);
        env.assertInvariants();
    }

    @Test
    void eightThreadsRetryingTheSameCaptureKeyProduceOneEffectAndOneAnswer() throws Exception {
        UUID id = created("m1", 10_000);
        authorize(id, key());
        String k = key();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> fs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                for (int attempt = 0; attempt < 50; attempt++) {
                    try { return capture(id, k).response().body(); }
                    catch (IdempotencyException e) { Thread.sleep(5); }          // 409 in progress: a real client retries
                }
                throw new AssertionError("never finished");
            }));
        }
        go.countDown();
        Set<String> bodies = new HashSet<>();
        for (var f : fs) bodies.add(f.get());
        pool.shutdown();
        assertThat(bodies).hasSize(1);
        assertThat(events("payment.captured", id)).isEqualTo(1);
        assertThat(pspCalls("pay_" + id, "CAPTURE")).isEqualTo(1);
        env.assertInvariants();
    }

    // ---- refunds
    @Test
    void partialThenFullRefundWalksTheStatusesAndTheLedger() {
        UUID id = captured("m1", 10_000);
        assertThat(parse(refund(id, key(), 3_000)).status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(natural("merchant:m1:payable")).as("merchant bears floor(3000 * 9800 / 10000) = 2940").isEqualTo(6_860);
        assertThat(natural("fees:revenue")).as("we give back the other 60 of the 200 fee").isEqualTo(140);
        assertThat(natural("psp_clearing")).isEqualTo(7_000);
        assertThat(natural("refunds:pending")).isZero();
        var v = parse(refund(id, key(), 7_000));
        assertThat(v.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(v.refundedAmount()).isEqualTo(10_000);
        assertThat(events("payment.refunded", id)).isEqualTo(2);
        assertThat(natural("merchant:m1:payable")).as("a full refund leaves the merchant at exactly zero").isZero();
        assertThat(natural("fees:revenue")).isZero();
        assertThat(natural("psp_clearing")).isZero();
        assertThatThrownBy(() -> refund(id, key(), 1)).isInstanceOf(InvalidStateException.class);
        env.assertInvariants();
    }

    @Test
    void refundMoreThanCapturedIsRejected() {
        UUID id = captured("m1", 1_000);
        assertThatThrownBy(() -> refund(id, key(), 1_001)).isInstanceOf(InvalidStateException.class).hasMessageContaining("exceeds");
        assertThatThrownBy(() -> refund(created("m1", 5), key(), 1)).isInstanceOf(InvalidStateException.class);
        env.assertInvariants();
    }

    @Test
    void refundIsRefusedBeforeTheProviderIsCalledWhenTheMerchantAlreadyWasPaidOut() {
        UUID id = captured("m1", 10_000);
        env.ledger.post(new Records.PostRequest(Records.Kind.PAYOUT, "payout-1", "payout",
                List.of(new Records.LineRequest("merchant:m1:payable", Side.D, 9_800), new Records.LineRequest("bank:settlement", Side.C, 9_800))));
        assertThatThrownBy(() -> refund(id, key(), 500)).isInstanceOf(InsufficientFundsException.class);
        assertThat(env.jdbc.sql("SELECT count(*) FROM psp_transactions WHERE kind = 'REFUND'").query(Long.class).single()).isZero();
        assertThat(pay.get(id).refundReserved()).isZero();
        env.assertInvariants();
    }

    @Test
    void declinedRefundReleasesTheReservation() {
        UUID id = captured("m1", 10_000);
        env.psp.setPlan(c -> c.kind() == PspSimulator.Kind.REFUND ? Behavior.DECLINE : Behavior.APPROVE);
        var v = parse(refund(id, key(), 2_000));
        assertThat(v.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(v.refundedAmount()).isZero();
        assertThat(v.refundReserved()).isZero();
        assertThat(natural("merchant:m1:payable")).isEqualTo(9_800);
        assertThat(natural("refunds:pending")).isZero();
        assertThat(events("payment.refund_failed", id)).isEqualTo(1);
        env.assertInvariants();
    }

    @Test
    void concurrentRefundsNeverExceedTheCapturedAmount() throws Exception {
        UUID id = captured("m1", 10_000);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> fs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                try { refund(id, key(), 2_000); return true; } catch (InvalidStateException e) { return false; }
            }));
        }
        go.countDown();
        long ok = 0;
        for (var f : fs) { if (f.get()) ok++; }
        pool.shutdown();
        assertThat(ok).as("10,000 captured allows exactly five refunds of 2,000").isEqualTo(5);
        assertThat(pay.get(id).status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(pay.get(id).refundedAmount()).isEqualTo(10_000);
        env.assertInvariants();
    }

    @Test
    void manualPostingsCannotTouchPaymentAccounts() {
        captured("m1", 1_000);
        assertThatThrownBy(() -> env.ledger.post(new Records.PostRequest("x", null,
                List.of(new Records.LineRequest("merchant:m1:payable", Side.D, 1), new Records.LineRequest("psp_clearing", Side.C, 1)))))
                .isInstanceOf(InvalidEntryException.class).hasMessageContaining("managed by payment flows");
    }

    // ---- state machine
    @Test
    void stateMachineIsConsistentAndRandomWalksNeverEscapeIt() {
        for (PaymentStatus s : PaymentStatus.values()) {
            assertThat(s.isTerminal()).isEqualTo(s.next().isEmpty());
            for (PaymentStatus t : PaymentStatus.values()) {
                assertThat(s.canMoveTo(t)).isEqualTo(s.next().contains(t));
            }
        }
        Random r = new Random(7);
        for (int walk = 0; walk < 2_000; walk++) {
            PaymentStatus s = PaymentStatus.CREATED;
            for (int step = 0; step < 12 && !s.isTerminal(); step++) {
                List<PaymentStatus> options = new ArrayList<>(s.next());
                s = options.get(r.nextInt(options.size()));
            }
            assertThat(s).isNotNull();
        }
        assertThat(PaymentStatus.CAPTURED.canMoveTo(PaymentStatus.AUTHORIZED)).isFalse();
        assertThat(PaymentStatus.REFUNDED.canMoveTo(PaymentStatus.CAPTURED)).isFalse();
        assertThat(PaymentStatus.FAILED.next()).isEmpty();
    }
}
