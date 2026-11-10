package dev.counterfoil.payments;

import dev.counterfoil.config.CounterfoilProperties;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A stand-in for a remote payment service provider (ADR-009). Its state lives in its own table and is committed
 * independently of the caller's transactions, exactly like a remote system: it can apply an operation and then
 * "time out" before telling the caller, which is the dangerous case the recovery logic exists for. Operations
 * are idempotent per (reference, kind): asking again returns the stored outcome and never applies twice.
 */
@Component
public class PspSimulator {
    public enum Kind { AUTHORIZE, CAPTURE, VOID, REFUND }

    public enum Outcome { APPROVED, DECLINED }

    public enum Behavior { APPROVE, DECLINE, TIMEOUT_BEFORE, TIMEOUT_AFTER }

    public record Call(String pspRef, Kind kind, long amount, int attempt) {}

    public static class PspTimeoutException extends RuntimeException {
        private final boolean applied;

        public PspTimeoutException(boolean applied) {
            super(applied ? "PSP timed out after applying the operation" : "PSP timed out before applying the operation");
            this.applied = applied;
        }

        /** Visible to tests only: a real caller cannot know. */
        public boolean applied() {
            return applied;
        }
    }

    private final JdbcClient jdbc;
    private final ConcurrentHashMap<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
    private volatile Function<Call, Behavior> plan;

    public PspSimulator(JdbcClient jdbc, CounterfoilProperties props) {
        this.jdbc = jdbc;
        var p = props.psp();
        this.plan = call -> {
            if (call.kind() == Kind.VOID) {
                return Behavior.APPROVE;              // voiding an authorization never fails in this model
            }
            double u = new Random(Objects.hash(p.seed(), call.pspRef(), call.kind(), call.attempt())).nextDouble();
            if (u < p.pTimeoutBefore()) return Behavior.TIMEOUT_BEFORE;
            if (u < p.pTimeoutBefore() + p.pTimeoutAfter()) return Behavior.TIMEOUT_AFTER;
            if (u < p.pTimeoutBefore() + p.pTimeoutAfter() + p.pDecline()) return Behavior.DECLINE;
            return Behavior.APPROVE;
        };
    }

    /** Tests install a scripted fault plan; null restores nothing, callers keep their own reference to the default. */
    public void setPlan(Function<Call, Behavior> plan) {
        this.plan = plan;
    }

    public Function<Call, Behavior> plan() {
        return plan;
    }

    public Optional<Outcome> lookup(String pspRef, Kind kind) {
        return jdbc.sql("SELECT outcome FROM psp_transactions WHERE psp_ref = :r AND kind = :k")
                .param("r", pspRef).param("k", kind.name()).query(String.class).optional().map(Outcome::valueOf);
    }

    public Outcome call(String pspRef, Kind kind, long amount) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("the PSP must be called outside a database transaction: its state is independent of ours");
        }
        Optional<Outcome> existing = lookup(pspRef, kind);
        if (existing.isPresent()) {
            return existing.get();                    // idempotent: never applies twice
        }
        int attempt = attempts.computeIfAbsent(pspRef + "|" + kind, k -> new AtomicInteger()).incrementAndGet();
        Behavior b = plan.apply(new Call(pspRef, kind, amount, attempt));
        switch (b) {
            case TIMEOUT_BEFORE -> throw new PspTimeoutException(false);
            case TIMEOUT_AFTER -> {
                record(pspRef, kind, amount, Outcome.APPROVED);
                throw new PspTimeoutException(true);
            }
            case DECLINE -> {
                record(pspRef, kind, amount, Outcome.DECLINED);
                return Outcome.DECLINED;
            }
            default -> {
                record(pspRef, kind, amount, Outcome.APPROVED);
                return Outcome.APPROVED;
            }
        }
    }

    private void record(String ref, Kind kind, long amount, Outcome outcome) {
        // its own (auto-commit) statement: the PSP's state is not part of the caller's transaction
        jdbc.sql("INSERT INTO psp_transactions (psp_ref, kind, amount, outcome) VALUES (:r, :k, :a, :o) ON CONFLICT DO NOTHING")
                .param("r", ref).param("k", kind.name()).param("a", amount).param("o", outcome.name()).update();
    }
}
