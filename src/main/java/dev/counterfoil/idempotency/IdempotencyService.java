package dev.counterfoil.idempotency;

import dev.counterfoil.config.TxRunner;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Exactly-one-effect for retried requests (I5).
 *
 * Single-transaction form: the key row is inserted inside the same transaction as the business effect.
 * A concurrent duplicate blocks on the primary key until the first transaction ends: if it committed, the
 * duplicate replays the stored response; if it rolled back, the duplicate proceeds. A crash leaves nothing.
 * Only successful outcomes are stored; a failed request rolls back and can be retried.
 *
 * Two-phase form (PSP-backed payments): {@link #begin} commits an IN_PROGRESS row first, {@link #complete}
 * finishes it in the transaction that records the outcome.
 */
@Service
public class IdempotencyService {
    public record Response(int status, String body, String resourceType, String resourceId) {}

    public record Outcome(Response response, boolean replayed) {}

    private final JdbcClient jdbc;
    private final TxRunner tx;

    public IdempotencyService(JdbcClient jdbc, TxRunner tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** Runs {@code work} at most once per (scope, key); later identical requests get the stored response. */
    public Outcome runAtomic(String scope, String key, String requestHash, Supplier<Response> work) {
        return tx.run(() -> {
            boolean inserted = jdbc.sql("""
                    INSERT INTO idempotency_keys (scope, key, request_hash, state) VALUES (:s, :k, :h, 'IN_PROGRESS')
                    ON CONFLICT (scope, key) DO NOTHING""")
                    .param("s", scope).param("k", key).param("h", requestHash).update() == 1;
            if (!inserted) {
                return new Outcome(existing(scope, key, requestHash), true);
            }
            Response r = work.get();
            completeInTx(scope, key, r);
            return new Outcome(r, false);
        });
    }

    /** Looks up a key; throws on a different request body or a request still in progress. */
    private Response existing(String scope, String key, String requestHash) {
        var row = jdbc.sql("SELECT request_hash, state, response_status, response_body, resource_type, resource_id FROM idempotency_keys WHERE scope = :s AND key = :k")
                .param("s", scope).param("k", key)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getObject(3, Integer.class), rs.getString(4), rs.getString(5), rs.getString(6)})
                .single();
        if (!requestHash.equals(row[0])) {
            throw IdempotencyException.mismatch();
        }
        if (!"COMPLETED".equals(row[1])) {
            throw IdempotencyException.inProgress();
        }
        return new Response((Integer) row[2], (String) row[3], (String) row[4], (String) row[5]);
    }

    // ---------------------------------------------------------------- two-phase form
    public sealed interface Begin permits Begin.Fresh, Begin.Replay {
        record Fresh() implements Begin {}

        record Replay(Response response) implements Begin {}
    }

    /** Phase 1 (own transaction): claims the key, or returns the stored response of a finished request. */
    public Begin begin(String scope, String key, String requestHash) {
        return tx.run(() -> beginInTx(scope, key, requestHash));
    }

    /** Phase 1 inside the caller's transaction, so the claim commits together with the intent it protects. */
    public Begin beginInTx(String scope, String key, String requestHash) {
        boolean inserted = jdbc.sql("""
                INSERT INTO idempotency_keys (scope, key, request_hash, state) VALUES (:s, :k, :h, 'IN_PROGRESS')
                ON CONFLICT (scope, key) DO NOTHING""")
                .param("s", scope).param("k", key).param("h", requestHash).update() == 1;
        return inserted ? new Begin.Fresh() : new Begin.Replay(existing(scope, key, requestHash));
    }

    /** Phase 2 (call inside the transaction that records the outcome). */
    public void completeInTx(String scope, String key, Response r) {
        int n = jdbc.sql("""
                UPDATE idempotency_keys SET state = 'COMPLETED', response_status = :st, response_body = :b,
                       resource_type = :rt, resource_id = :rid, completed_at = now()
                 WHERE scope = :s AND key = :k""")
                .param("st", r.status()).param("b", r.body()).param("rt", r.resourceType()).param("rid", r.resourceId())
                .param("s", scope).param("k", key).update();
        if (n != 1) {
            throw new IllegalStateException("idempotency row vanished for " + scope + "/" + key);
        }
    }

    public Optional<Response> find(String scope, String key) {
        return jdbc.sql("SELECT response_status, response_body, resource_type, resource_id FROM idempotency_keys WHERE scope = :s AND key = :k AND state = 'COMPLETED'")
                .param("s", scope).param("k", key)
                .query((rs, i) -> new Response(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4))).optional();
    }
}
