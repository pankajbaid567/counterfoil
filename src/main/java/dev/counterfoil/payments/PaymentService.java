package dev.counterfoil.payments;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.counterfoil.config.CounterfoilProperties;
import dev.counterfoil.config.TxRunner;
import dev.counterfoil.idempotency.IdempotencyException;
import dev.counterfoil.idempotency.IdempotencyService;
import dev.counterfoil.idempotency.IdempotencyService.Begin;
import dev.counterfoil.idempotency.IdempotencyService.Outcome;
import dev.counterfoil.idempotency.IdempotencyService.Response;
import dev.counterfoil.ledger.*;
import dev.counterfoil.ledger.Records.*;
import dev.counterfoil.outbox.OutboxService;
import dev.counterfoil.payments.Payments.*;
import dev.counterfoil.payments.PspSimulator.Kind;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Payment orchestration (ADR-006, ADR-007).
 *
 * PSP-backed operations run in three steps: (1) one transaction commits the intent (idempotency claim, status
 * change, an op row); (2) the PSP is called outside any transaction, idempotently by reference; (3) one
 * transaction applies the outcome (ledger entry, status, outbox event, idempotency completion) atomically.
 * A crash or a timeout between 1 and 3 leaves a PENDING op that the recovery sweeper (or a retry) finishes.
 */
@Service
public class PaymentService {
    public static final String CLEARING = "psp_clearing", FEES = "fees:revenue", REFUND_PENDING = "refunds:pending", BANK = "bank:settlement";
    public static final String CURRENCY = "INR";

    public static String payable(String merchantId) {
        return "merchant:" + merchantId + ":payable";
    }

    private final JdbcClient jdbc;
    private final TxRunner tx;
    private final LedgerService ledger;
    private final IdempotencyService idem;
    private final PspSimulator psp;
    private final OutboxService outbox;
    private final CounterfoilProperties props;
    private final ObjectMapper mapper;

    public PaymentService(JdbcClient jdbc, TxRunner tx, LedgerService ledger, IdempotencyService idem, PspSimulator psp, OutboxService outbox,
                          CounterfoilProperties props, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.ledger = ledger;
        this.idem = idem;
        this.psp = psp;
        this.outbox = outbox;
        this.props = props;
        this.mapper = mapper;
    }

    /** The fixed part of the chart of accounts (idempotent). */
    public void ensureSystemAccounts() {
        tx.runVoid(() -> {
            ledger.ensureAccount(CLEARING, "PSP clearing", AccountType.ASSET, CURRENCY, true);
            ledger.ensureAccount(FEES, "Fee revenue", AccountType.REVENUE, CURRENCY, true);
            ledger.ensureAccount(REFUND_PENDING, "Refunds pending", AccountType.LIABILITY, CURRENCY, true);
            ledger.ensureAccount(BANK, "Settlement bank", AccountType.ASSET, CURRENCY, true);
        });
    }

    // ------------------------------------------------------------------ create
    public Outcome create(String scope, String key, String requestHash, CreateRequest req) {
        if (req == null || req.merchantId() == null || !req.merchantId().matches("^[a-z0-9_\\-]{1,60}$")) {
            throw new InvalidEntryException("merchant_id must be 1-60 characters of a-z 0-9 _ -");
        }
        if (req.amount() <= 0 || req.amount() > 100_000_000_000L) {
            throw new InvalidEntryException("amount must be positive and at most 100000000000 minor units");
        }
        if (!CURRENCY.equals(req.currency())) {
            throw new InvalidEntryException("only " + CURRENCY + " is supported");
        }
        return idem.runAtomic(scope, key, requestHash, () -> {
            ledger.ensureAccount(payable(req.merchantId()), "Payable to " + req.merchantId(), AccountType.LIABILITY, CURRENCY, false);
            UUID id = jdbc.sql("INSERT INTO payments (merchant_id, amount, currency, status) VALUES (:m, :a, :c, 'CREATED') RETURNING id")
                    .param("m", req.merchantId()).param("a", req.amount()).param("c", CURRENCY).query(UUID.class).single();
            jdbc.sql("INSERT INTO payment_events (payment_id, seq, from_status, to_status) VALUES (:p, 1, NULL, 'CREATED')").param("p", id).update();
            PaymentView v = view(id);
            outbox.enqueue("payment.created", id.toString(), snapshot(v, null));
            return response(201, v, "payment", id.toString());
        });
    }

    // ------------------------------------------------------------------ PSP-backed operations
    public Outcome authorize(String scope, String key, String hash, UUID paymentId) {
        return runOp(scope, key, hash, paymentId, Kind.AUTHORIZE, 0);
    }

    public Outcome capture(String scope, String key, String hash, UUID paymentId) {
        return runOp(scope, key, hash, paymentId, Kind.CAPTURE, 0);
    }

    public Outcome voidPayment(String scope, String key, String hash, UUID paymentId) {
        return runOp(scope, key, hash, paymentId, Kind.VOID, 0);
    }

    public Outcome refund(String scope, String key, String hash, UUID paymentId, RefundRequest req) {
        if (req == null || req.amount() <= 0) {
            throw new InvalidEntryException("refund amount must be positive");
        }
        return runOp(scope, key, hash, paymentId, Kind.REFUND, req.amount());
    }

    record Op(UUID id, UUID paymentId, Kind kind, UUID refundId, long amount, String pspRef, String scope, String key, String state) {}

    private Outcome runOp(String scope, String key, String hash, UUID paymentId, Kind kind, long refundAmount) {
        Begin begin;
        try {
            begin = tx.run(() -> {
                Begin b = idem.beginInTx(scope, key, hash);
                if (b instanceof Begin.Replay) {
                    return b;
                }
                prepare(paymentId, kind, refundAmount, scope, key);
                return b;
            });
        } catch (IdempotencyException e) {
            if (!"REQUEST_IN_PROGRESS".equals(e.code())) {
                throw e;
            }
            // An earlier attempt with this key has not finished (perhaps it is waiting on the PSP). Help finish it.
            Op op = findOp(scope, key).orElseThrow(() -> e);
            return new Outcome(finish(op), true);
        }
        if (begin instanceof Begin.Replay r) {
            return new Outcome(r.response(), true);
        }
        Op op = findOp(scope, key).orElseThrow();
        return new Outcome(finish(op), false);
    }

    /** Step 1 (inside the caller's transaction): validate, move to the in-flight status, record the op. */
    private void prepare(UUID paymentId, Kind kind, long refundAmount, String scope, String key) {
        PaymentRow p = lock(paymentId);
        UUID opId = UUID.randomUUID();
        switch (kind) {
            case AUTHORIZE -> {
                require(p, PaymentStatus.CREATED, "authorize");
                moveTo(p, PaymentStatus.AUTHORIZING, "authorization requested");
                insertOp(opId, p, kind, null, p.amount, "pay_" + p.id, scope, key);
            }
            case CAPTURE -> {
                require(p, PaymentStatus.AUTHORIZED, "capture");
                moveTo(p, PaymentStatus.CAPTURING, "capture requested");
                insertOp(opId, p, kind, null, p.amount, "pay_" + p.id, scope, key);
            }
            case VOID -> {
                require(p, PaymentStatus.AUTHORIZED, "void");
                moveTo(p, PaymentStatus.VOIDING, "void requested");
                insertOp(opId, p, kind, null, p.amount, "pay_" + p.id, scope, key);
            }
            case REFUND -> {
                if (!p.status.isRefundable()) {
                    throw new InvalidStateException("a " + p.status + " payment cannot be refunded");
                }
                long available = p.captured - p.refunded - p.reserved;
                if (refundAmount > available) {
                    throw new InvalidStateException("refund of " + refundAmount + " exceeds the refundable amount " + available);
                }
                UUID refundId = jdbc.sql("INSERT INTO refunds (payment_id, amount, status) VALUES (:p, :a, 'REFUNDING') RETURNING id")
                        .param("p", p.id).param("a", refundAmount).query(UUID.class).single();
                // Reserve the money first: Dr merchant payable (their share) and Dr fees:revenue (our share) / Cr refunds:pending.
                // If the merchant cannot cover their share, this throws, the whole intent rolls back, and the PSP is never called.
                long merchantShare = merchantPortion(p, p.refunded + p.reserved + refundAmount) - merchantPortion(p, p.refunded + p.reserved);
                long feeShare = refundAmount - merchantShare;
                List<LineRequest> reserve = new ArrayList<>();
                if (merchantShare > 0) {
                    reserve.add(new LineRequest(payable(p.merchantId), Side.D, merchantShare));
                }
                if (feeShare > 0) {
                    reserve.add(new LineRequest(FEES, Side.D, feeShare));
                }
                reserve.add(new LineRequest(REFUND_PENDING, Side.C, refundAmount));
                ledger.postInTx(new PostRequest(Kind2.REFUND, "refund:" + refundId + ":reserve", "refund reservation", reserve));
                jdbc.sql("UPDATE payments SET refund_reserved = refund_reserved + :a, version = version + 1, updated_at = now() WHERE id = :p")
                        .param("a", refundAmount).param("p", p.id).update();
                insertOp(opId, p, kind, refundId, refundAmount, "ref_" + refundId, scope, key);
            }
        }
    }

    /** Step 2 and 3. Safe to call any number of times, from any thread or instance. */
    Response finish(Op op) {
        if ("DONE".equals(op.state)) {
            return idem.find(op.scope, op.key).orElseThrow();
        }
        PspSimulator.Outcome outcome;
        try {
            outcome = psp.call(op.pspRef, op.kind, op.amount);
        } catch (PspSimulator.PspTimeoutException timeout) {
            return processing(op);                    // outcome unknown: stays PENDING for the sweeper or a retry
        }
        return tx.run(() -> apply(op.id, outcome));
    }

    private Response apply(UUID opId, PspSimulator.Outcome outcome) {
        UUID paymentId = jdbc.sql("SELECT payment_id FROM payment_ops WHERE id = :i").param("i", opId).query(UUID.class).single();
        PaymentRow p = lock(paymentId);                // always lock the payment row first, then re-read the op
        Op op = findOpById(opId).orElseThrow();
        if ("DONE".equals(op.state)) {
            return idem.find(op.scope, op.key).orElseThrow();
        }
        boolean approved = outcome == PspSimulator.Outcome.APPROVED;
        Object extra = null;
        switch (op.kind) {
            case AUTHORIZE -> {
                if (approved) {
                    moveTo(p, PaymentStatus.AUTHORIZED, "authorized");
                    outbox.enqueue("payment.authorized", p.id.toString(), snapshot(view(p.id), null));
                } else {
                    fail(p, "authorization declined");
                }
            }
            case CAPTURE -> {
                if (approved) {
                    long fee = Math.multiplyExact(p.amount, (long) props.fees().bps()) / 10_000L;
                    List<LineRequest> lines = new ArrayList<>(List.of(new LineRequest(CLEARING, Side.D, p.amount),
                            new LineRequest(payable(p.merchantId), Side.C, p.amount - fee)));
                    if (fee > 0) {
                        lines.add(new LineRequest(FEES, Side.C, fee));
                    }
                    ledger.postInTx(new PostRequest(Kind2.PAYMENT_CAPTURE, "payment:" + p.id, "capture", lines));
                    jdbc.sql("UPDATE payments SET captured_amount = amount, fee = :f WHERE id = :p").param("f", fee).param("p", p.id).update();
                    moveTo(p, PaymentStatus.CAPTURED, "captured");
                    outbox.enqueue("payment.captured", p.id.toString(), snapshot(view(p.id), null));
                } else {
                    fail(p, "capture declined");
                }
            }
            case VOID -> {
                moveTo(p, PaymentStatus.VOIDED, "voided");
                outbox.enqueue("payment.voided", p.id.toString(), snapshot(view(p.id), null));
            }
            case REFUND -> {
                long a = op.amount;
                if (approved) {
                    ledger.postInTx(new PostRequest(Kind2.REFUND, "refund:" + op.refundId + ":complete", "refund completion",
                            List.of(new LineRequest(REFUND_PENDING, Side.D, a), new LineRequest(CLEARING, Side.C, a))));
                    jdbc.sql("UPDATE refunds SET status = 'REFUNDED', updated_at = now() WHERE id = :r").param("r", op.refundId).update();
                    jdbc.sql("UPDATE payments SET refund_reserved = refund_reserved - :a, refunded_amount = refunded_amount + :a WHERE id = :p")
                            .param("a", a).param("p", p.id).update();
                    PaymentRow now = lock(p.id);
                    moveTo(now, now.refunded == now.captured ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED, "refunded " + a);
                    outbox.enqueue("payment.refunded", p.id.toString(), snapshot(view(p.id), op.refundId));
                } else {
                    UUID reserve = jdbc.sql("SELECT public_id FROM journal_entries WHERE reference = :r").param("r", "refund:" + op.refundId + ":reserve")
                            .query(UUID.class).single();
                    ledger.reverseInTx(reserve, "refund:" + op.refundId + ":release");
                    jdbc.sql("UPDATE refunds SET status = 'FAILED', updated_at = now() WHERE id = :r").param("r", op.refundId).update();
                    jdbc.sql("UPDATE payments SET refund_reserved = refund_reserved - :a, version = version + 1, updated_at = now() WHERE id = :p")
                            .param("a", a).param("p", p.id).update();
                    outbox.enqueue("payment.refund_failed", p.id.toString(), snapshot(view(p.id), op.refundId));
                }
                extra = op.refundId;
            }
        }
        Response r = response(200, view(p.id), "payment", p.id.toString());
        idem.completeInTx(op.scope, op.key, r);
        jdbc.sql("UPDATE payment_ops SET state = 'DONE', completed_at = now() WHERE id = :i").param("i", opId).update();
        return r;
    }

    private void fail(PaymentRow p, String reason) {
        jdbc.sql("UPDATE payments SET failure_reason = :r WHERE id = :p").param("r", reason).param("p", p.id).update();
        moveTo(p, PaymentStatus.FAILED, reason);
        outbox.enqueue("payment.failed", p.id.toString(), snapshot(view(p.id), null));
    }

    // ------------------------------------------------------------------ recovery
    /** Finishes operations that were interrupted or whose PSP answer never arrived. Returns how many were finished. */
    public int recoverStale(long olderThanSeconds, int limit) {
        List<UUID> ids = jdbc.sql("""
                SELECT id FROM payment_ops WHERE state = 'PENDING' AND created_at < now() - make_interval(secs => :s)
                 ORDER BY created_at LIMIT :l""").param("s", olderThanSeconds).param("l", limit).query(UUID.class).list();
        int done = 0;
        for (UUID id : ids) {
            Optional<Op> op = findOpById(id);
            if (op.isPresent() && "PENDING".equals(op.get().state)) {
                Response r = finish(op.get());
                if (r.status() != 202) {
                    done++;
                }
            }
        }
        return done;
    }

    public long pendingOps() {
        return jdbc.sql("SELECT count(*) FROM payment_ops WHERE state = 'PENDING'").query(Long.class).single();
    }

    // ------------------------------------------------------------------ reads
    public PaymentView get(UUID id) {
        return view(id);
    }

    PaymentView view(UUID id) {
        return jdbc.sql("""
                SELECT id, merchant_id, amount, currency, status, fee, captured_amount, refunded_amount, refund_reserved, failure_reason, version, created_at, updated_at
                  FROM payments WHERE id = :i""").param("i", id)
                .query((rs, n) -> new PaymentView(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), rs.getString(4).trim(),
                        PaymentStatus.valueOf(rs.getString(5)), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getString(10),
                        rs.getLong(11), rs.getTimestamp(12).toInstant(), rs.getTimestamp(13).toInstant()))
                .optional().orElseThrow(() -> new PaymentNotFoundException(id));
    }

    // ------------------------------------------------------------------ helpers
    private record PaymentRow(UUID id, String merchantId, long amount, PaymentStatus status, long captured, long refunded, long reserved, long fee) {}

    private PaymentRow lock(UUID id) {
        return jdbc.sql("SELECT id, merchant_id, amount, status, captured_amount, refunded_amount, refund_reserved, fee FROM payments WHERE id = :i FOR UPDATE")
                .param("i", id)
                .query((rs, n) -> new PaymentRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), PaymentStatus.valueOf(rs.getString(4)),
                        rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8)))
                .optional().orElseThrow(() -> new PaymentNotFoundException(id));
    }

    /**
     * The merchant's share of the first {@code cumulativeRefund} minor units refunded: floor(cumulative * net / amount).
     * Using the cumulative figure keeps rounding exact: after a full refund the merchant has been charged exactly
     * amount - fee and the fee revenue exactly fee, whatever the sequence of partial refunds.
     */
    static long merchantPortion(PaymentRow p, long cumulativeRefund) {
        java.math.BigInteger net = java.math.BigInteger.valueOf(p.amount - p.fee);
        return java.math.BigInteger.valueOf(cumulativeRefund).multiply(net).divide(java.math.BigInteger.valueOf(p.amount)).longValueExact();
    }

    private void require(PaymentRow p, PaymentStatus expected, String what) {
        if (p.status != expected) {
            throw new InvalidStateException("cannot " + what + " a payment in status " + p.status + " (needs " + expected + ")");
        }
    }

    private void moveTo(PaymentRow p, PaymentStatus to, String detail) {
        if (!p.status.canMoveTo(to)) {
            throw new IllegalStateException("illegal payment transition " + p.status + " -> " + to);
        }
        jdbc.sql("UPDATE payments SET status = :s, version = version + 1, updated_at = now() WHERE id = :p").param("s", to.name()).param("p", p.id).update();
        jdbc.sql("""
                INSERT INTO payment_events (payment_id, seq, from_status, to_status, detail)
                SELECT :p, COALESCE(max(seq), 0) + 1, :f, :t, :d FROM payment_events WHERE payment_id = :p""")
                .param("p", p.id).param("f", p.status.name()).param("t", to.name()).param("d", detail).update();
    }

    private void insertOp(UUID id, PaymentRow p, Kind kind, UUID refundId, long amount, String pspRef, String scope, String key) {
        jdbc.sql("""
                INSERT INTO payment_ops (id, payment_id, kind, refund_id, amount, psp_ref, idem_scope, idem_key, state)
                VALUES (:i, :p, :k, :r, :a, :ref, :s, :key, 'PENDING')""")
                .param("i", id).param("p", p.id).param("k", kind.name()).param("r", refundId).param("a", amount).param("ref", pspRef)
                .param("s", scope).param("key", key).update();
    }

    private static final String OP_COLS = "id, payment_id, kind, refund_id, amount, psp_ref, idem_scope, idem_key, state";

    private static Op mapOp(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Op(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), Kind.valueOf(rs.getString(3)), rs.getObject(4, UUID.class),
                rs.getLong(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9));
    }

    Optional<Op> findOp(String scope, String key) {
        return jdbc.sql("SELECT " + OP_COLS + " FROM payment_ops WHERE idem_scope = :s AND idem_key = :k").param("s", scope).param("k", key)
                .query((rs, n) -> mapOp(rs)).optional();
    }

    Optional<Op> findOpById(UUID id) {
        return jdbc.sql("SELECT " + OP_COLS + " FROM payment_ops WHERE id = :i").param("i", id).query((rs, n) -> mapOp(rs)).optional();
    }

    private Map<String, Object> snapshot(PaymentView v, UUID refundId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("payment_id", v.id());
        m.put("merchant_id", v.merchantId());
        m.put("amount", v.amount());
        m.put("currency", v.currency());
        m.put("status", v.status());
        m.put("fee", v.fee());
        m.put("captured_amount", v.capturedAmount());
        m.put("refunded_amount", v.refundedAmount());
        if (refundId != null) {
            m.put("refund_id", refundId);
        }
        return m;
    }

    private Response response(int status, PaymentView v, String type, String id) {
        try {
            return new Response(status, mapper.writeValueAsString(v), type, id);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Response processing(Op op) {
        return new Response(202, "{\"status\":\"processing\",\"payment_id\":\"" + op.paymentId + "\",\"message\":\"the payment provider has not answered yet; retry with the same Idempotency-Key\"}",
                "payment", op.paymentId.toString());
    }

    /** Alias so the code above reads clearly next to the PSP's own Kind enum. */
    private static final class Kind2 {
        static final Records.Kind REFUND = Records.Kind.REFUND, PAYMENT_CAPTURE = Records.Kind.PAYMENT_CAPTURE;
    }
}
