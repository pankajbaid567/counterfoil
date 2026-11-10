package dev.counterfoil.outbox;

import dev.counterfoil.config.CounterfoilProperties;
import dev.counterfoil.config.TxRunner;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Delivers outbox events (ADR-008). Claim (transaction, SKIP LOCKED, lease) then send (no transaction) then
 * record the result. Delivery is at-least-once: "sent but not recorded" cannot be avoided, so the receiver
 * de-duplicates on the stable event id. Several dispatchers can run at once.
 */
@Component
public class WebhookDispatcher {
    private record Claimed(long id, UUID eventId, String type, String aggregateId, String payload, Instant createdAt, int attempts) {}

    private final JdbcClient jdbc;
    private final TxRunner tx;
    private final CounterfoilProperties props;
    private final WebhookSender sender;
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile Runnable beforeMarkDelivered = () -> {};   // test hook: simulates a crash after a successful send

    public WebhookDispatcher(JdbcClient jdbc, TxRunner tx, CounterfoilProperties props, WebhookSender sender) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.props = props;
        this.sender = sender;
    }

    public void setBeforeMarkDelivered(Runnable r) {
        this.beforeMarkDelivered = r;
    }

    @Scheduled(fixedDelayString = "${counterfoil.webhook.poll-ms:500}")
    public void poll() {
        var w = props.webhook();
        if (w.dispatcherEnabled() && !w.targetUrl().isBlank()) {
            deliverDue(50);
        }
    }

    /** Claims up to {@code batch} due events and tries to deliver them. Returns how many were delivered. */
    public int deliverDue(int batch) {
        List<Claimed> claimed = claim(batch);
        int delivered = 0;
        for (Claimed c : claimed) {
            if (attempt(c)) {
                delivered++;
            }
        }
        return delivered;
    }

    private List<Claimed> claim(int batch) {
        return tx.run(() -> jdbc.sql("""
                UPDATE outbox SET locked_until = now() + make_interval(secs => :lease), attempts = attempts + 1
                 WHERE id IN (SELECT id FROM outbox
                               WHERE status = 'PENDING' AND next_attempt_at <= now() AND (locked_until IS NULL OR locked_until < now())
                               ORDER BY id LIMIT :n FOR UPDATE SKIP LOCKED)
                RETURNING id, event_id, type, aggregate_id, payload, created_at, attempts""")
                .param("lease", props.webhook().leaseSeconds()).param("n", batch)
                .query((rs, i) -> new Claimed(rs.getLong(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getTimestamp(6).toInstant(), rs.getInt(7))).list());
    }

    private boolean attempt(Claimed c) {
        String body = "{\"id\":\"" + c.eventId + "\",\"type\":\"" + c.type + "\",\"created\":" + c.createdAt.getEpochSecond() + ",\"data\":" + c.payload + "}";
        long ts = Instant.now().getEpochSecond();
        Map<String, String> headers = Map.of("Counterfoil-Event-Id", c.eventId.toString(),
                "Counterfoil-Signature", WebhookSigner.sign(props.webhook().secret(), ts, body));
        String error = null;
        try {
            int status = sender.post(props.webhook().targetUrl(), headers, body);
            if (status / 100 != 2) {
                error = "receiver answered HTTP " + status;
            }
        } catch (IOException | RuntimeException e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        if (error == null) {
            beforeMarkDelivered.run();
            tx.runVoid(() -> jdbc.sql("UPDATE outbox SET status = 'DELIVERED', delivered_at = now(), locked_until = NULL, last_error = NULL WHERE id = :i")
                    .param("i", c.id).update());
            sent.incrementAndGet();
            return true;
        }
        failed.incrementAndGet();
        final String err = error;
        tx.runVoid(() -> {
            if (c.attempts >= props.webhook().maxAttempts()) {
                jdbc.sql("UPDATE outbox SET status = 'DEAD', locked_until = NULL, last_error = :e WHERE id = :i").param("e", err).param("i", c.id).update();
            } else {
                long delayMs = backoffMs(c.attempts);
                jdbc.sql("""
                        UPDATE outbox SET locked_until = NULL, last_error = :e, next_attempt_at = now() + make_interval(secs => :s)
                         WHERE id = :i""").param("e", err).param("s", delayMs / 1000.0).param("i", c.id).update();
            }
        });
        return false;
    }

    /** Exponential backoff with jitter, capped at 60 s. */
    long backoffMs(int attempts) {
        long base = Math.min(60_000L, props.webhook().backoffBaseMs() * (1L << Math.min(attempts - 1, 16)));
        return (long) (base * (0.5 + ThreadLocalRandom.current().nextDouble()));
    }

    public long delivered() {
        return sent.get();
    }

    public long failedAttempts() {
        return failed.get();
    }

    public record Counts(long pending, long delivered, long dead) {}

    public Counts counts() {
        return new Counts(count("PENDING"), count("DELIVERED"), count("DEAD"));
    }

    private long count(String status) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE status = :s").param("s", status).query(Long.class).single();
    }
}
