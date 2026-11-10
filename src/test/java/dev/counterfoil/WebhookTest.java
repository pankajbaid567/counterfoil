package dev.counterfoil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.counterfoil.outbox.*;
import dev.counterfoil.payments.Payments.CreateRequest;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebhookTest {
    TestEnv env;
    final Map<String, Integer> applied = new ConcurrentHashMap<>();   // event id -> times the effect ran

    @BeforeEach
    void setUp() { env = new TestEnv("hook", 16); }

    /** A receiver that verifies, dedups in the database, and counts effects. */
    int deliverToReceiver(Map<String, String> headers, String body) {
        try {
            env.receiver.receive("test", env.props.webhook().secret(), headers.get("Counterfoil-Signature"), body,
                    json -> applied.merge(json.path("id").asText(), 1, Integer::sum));
            return 200;
        } catch (RuntimeException e) {
            return 400;
        }
    }

    void makePayments(int n) {
        for (int i = 0; i < n; i++) {
            env.payments.create("t", "k" + i, "h" + i, new CreateRequest("m" + (i % 3), 1_000 + i, "INR"));
        }
    }

    @Test
    void signatureRoundTripAndTamperDetection() {
        long now = Instant.now().getEpochSecond();
        String sig = WebhookSigner.sign("s3cret", now, "{\"a\":1}");
        assertThat(WebhookSigner.verify("s3cret", sig, "{\"a\":1}", now, 300)).isTrue();
        assertThat(WebhookSigner.verify("s3cret", sig, "{\"a\":2}", now, 300)).as("body changed").isFalse();
        assertThat(WebhookSigner.verify("other", sig, "{\"a\":1}", now, 300)).as("wrong secret").isFalse();
        assertThat(WebhookSigner.verify("s3cret", sig, "{\"a\":1}", now + 301, 300)).as("replayed too late").isFalse();
        assertThat(WebhookSigner.verify("s3cret", "garbage", "{\"a\":1}", now, 300)).isFalse();
        assertThat(WebhookSigner.verify("s3cret", null, "{\"a\":1}", now, 300)).isFalse();
    }

    @Test
    void everyEventIsDeliveredEvenWhenThirtyPercentFailAndTwentyPercentAreDuplicated() throws Exception {
        makePayments(60);
        Random r = new Random(11);
        AtomicInteger posts = new AtomicInteger();
        env.sender = (url, headers, body) -> {
            posts.incrementAndGet();
            double x = r.nextDouble();
            if (x < 0.30) throw new IOException("connection reset");               // never reached the receiver
            int status = deliverToReceiver(headers, body);
            if (x < 0.50) deliverToReceiver(headers, body);                        // network duplicated the request
            return status;
        };
        long deadline = System.currentTimeMillis() + 20_000;
        while (env.dispatcher.counts().pending() > 0 && System.currentTimeMillis() < deadline) {
            env.dispatcher.deliverDue(20);
            Thread.sleep(3);
        }
        var c = env.dispatcher.counts();
        assertThat(c.pending()).isZero();
        assertThat(c.dead()).isZero();
        assertThat(c.delivered()).isEqualTo(60);
        assertThat(applied).hasSize(60);
        assertThat(applied.values()).as("each event applied exactly once").allMatch(n -> n == 1);
        assertThat(posts.get()).as("retries really happened").isGreaterThan(60);
        env.assertInvariants();
    }

    @Test
    void crashAfterSendingButBeforeRecordingCausesAResendThatTheReceiverIgnores() {
        makePayments(1);
        env.sender = (url, headers, body) -> deliverToReceiver(headers, body);
        env.dispatcher.setBeforeMarkDelivered(() -> { throw new IllegalStateException("crash after send"); });
        assertThatThrownBy(() -> env.dispatcher.deliverDue(10)).isInstanceOf(IllegalStateException.class);
        assertThat(applied.values()).containsExactly(1);
        assertThat(env.dispatcher.counts().pending()).isEqualTo(1);
        env.dispatcher.setBeforeMarkDelivered(() -> {});
        env.jdbc.sql("UPDATE outbox SET locked_until = NULL").update();             // lease expiry, shortcut
        assertThat(env.dispatcher.deliverDue(10)).isEqualTo(1);
        assertThat(env.dispatcher.counts().delivered()).isEqualTo(1);
        assertThat(applied.values()).as("resent, not re-applied").containsExactly(1);
    }

    @Test
    void permanentlyFailingReceiverEndsInDeadAfterMaxAttempts() throws Exception {
        makePayments(1);
        env.sender = (url, headers, body) -> 500;
        long deadline = System.currentTimeMillis() + 15_000;
        while (env.dispatcher.counts().dead() == 0 && System.currentTimeMillis() < deadline) {
            env.dispatcher.deliverDue(10);
            Thread.sleep(5);
        }
        assertThat(env.dispatcher.counts().dead()).isEqualTo(1);
        assertThat(env.jdbc.sql("SELECT attempts FROM outbox").query(Integer.class).single()).isEqualTo(8);
    }

    @Test
    void parallelDispatchersNeverSendTheSameEventTwiceWhenAllSucceed() throws Exception {
        makePayments(100);
        AtomicInteger posts = new AtomicInteger();
        env.sender = (url, headers, body) -> { posts.incrementAndGet(); return deliverToReceiver(headers, body); };
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 4; i++) fs.add(pool.submit(() -> { while (env.dispatcher.deliverDue(7) > 0) { } }));
        for (var f : fs) f.get();
        pool.shutdown();
        assertThat(posts.get()).as("SKIP LOCKED means no overlap").isEqualTo(100);
        assertThat(env.dispatcher.counts().delivered()).isEqualTo(100);
    }

    @Test
    void receiverRejectsBadSignaturesAndRollsBackWhenTheEffectFails() {
        long now = Instant.now().getEpochSecond();
        String body = "{\"id\":\"evt-1\",\"type\":\"x\"}";
        assertThatThrownBy(() -> env.receiver.receive("t", "secret", "t=" + now + ",v1=00", body, j -> {}))
                .isInstanceOf(WebhookReceiver.InvalidSignatureException.class);
        String sig = WebhookSigner.sign("secret", now, body);
        assertThatThrownBy(() -> env.receiver.receive("t", "secret", sig, body, j -> { throw new IllegalStateException("boom"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(env.jdbc.sql("SELECT count(*) FROM inbound_events").query(Long.class).single()).as("id row rolled back").isZero();
        assertThat(env.receiver.receive("t", "secret", sig, body, j -> {})).isEqualTo(WebhookReceiver.Result.APPLIED);
        assertThat(env.receiver.receive("t", "secret", sig, body, j -> {})).isEqualTo(WebhookReceiver.Result.DUPLICATE);
    }

    @Test
    void pspSettlementWebhookIsAppliedOnceEvenIfDeliveredTwiceOrConcurrently() throws Exception {
        var created = env.payments.create("t", "c2", "c2", new CreateRequest("m1", 10_000, "INR"));
        UUID id = env.mapper.readValue(created.response().body(), dev.counterfoil.payments.Payments.PaymentView.class).id();
        env.payments.authorize("t", "a", "a", id);
        env.payments.capture("t", "cap", "cap", id);
        long now = Instant.now().getEpochSecond();
        String body = "{\"id\":\"evt-settle-1\",\"type\":\"psp.settlement.paid\",\"data\":{\"amount\":10000,\"reference\":\"batch-1\"}}";
        String sig = WebhookSigner.sign(env.props.webhook().secret(), now, body);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<WebhookReceiver.Result>> fs = new ArrayList<>();
        for (int i = 0; i < 6; i++) fs.add(pool.submit(() -> env.settlement.onPspWebhook(sig, body)));
        int appliedCount = 0;
        for (var f : fs) if (f.get() == WebhookReceiver.Result.APPLIED) appliedCount++;
        pool.shutdown();
        assertThat(appliedCount).isEqualTo(1);
        assertThat(env.ledger.balance("bank:settlement").natural()).isEqualTo(10_000);
        assertThat(env.ledger.balance("psp_clearing").natural()).isZero();
        env.assertInvariants();
    }
}
