package dev.counterfoil.payments;

import dev.counterfoil.admin.InvariantChecker;
import org.springframework.stereotype.Component;

/** Payment and outbox invariants (I4, I7), registered with the integrity checker. All derived from raw rows. */
@Component
public class PaymentInvariants {
    public PaymentInvariants(InvariantChecker checker) {
        checker.register("I4a.one-capture", "a payment has exactly one capture entry iff it was captured, and no capture entry is orphaned", """
                SELECT 'payment ' || p.id || ' captured=' || p.captured_amount || ' capture entries=' || COALESCE(c.n, 0)
                  FROM payments p LEFT JOIN (SELECT reference, count(*) AS n FROM journal_entries WHERE kind = 'PAYMENT_CAPTURE' GROUP BY reference) c
                    ON c.reference = 'payment:' || p.id
                 WHERE (p.captured_amount > 0) <> (COALESCE(c.n, 0) = 1) OR COALESCE(c.n, 0) > 1
                UNION ALL
                SELECT 'orphan capture entry ' || e.reference FROM journal_entries e
                 WHERE e.kind = 'PAYMENT_CAPTURE' AND NOT EXISTS (SELECT 1 FROM payments p WHERE 'payment:' || p.id = e.reference)""");
        checker.register("I4b.refunds", "refund counters equal the refund rows, and each refund has exactly the ledger entries its status implies", """
                SELECT 'payment ' || p.id || ' refunded ' || p.refunded_amount || ' vs done ' || COALESCE(r.done, 0)
                       || ', reserved ' || p.refund_reserved || ' vs pending ' || COALESCE(r.pend, 0)
                  FROM payments p LEFT JOIN (SELECT payment_id, SUM(amount) FILTER (WHERE status = 'REFUNDED') AS done,
                                                    SUM(amount) FILTER (WHERE status = 'REFUNDING') AS pend FROM refunds GROUP BY payment_id) r ON r.payment_id = p.id
                 WHERE p.refunded_amount <> COALESCE(r.done, 0) OR p.refund_reserved <> COALESCE(r.pend, 0)
                UNION ALL
                SELECT 'refund ' || x.id || ' status ' || x.status || ' reserve=' || x.reserve || ' complete=' || x.complete || ' release=' || x.release
                  FROM (SELECT r.id, r.status,
                               (SELECT count(*) FROM journal_entries WHERE reference = 'refund:' || r.id || ':reserve') AS reserve,
                               (SELECT count(*) FROM journal_entries WHERE reference = 'refund:' || r.id || ':complete') AS complete,
                               (SELECT count(*) FROM journal_entries WHERE reference = 'refund:' || r.id || ':release') AS release
                          FROM refunds r) x
                 WHERE NOT (x.reserve = 1 AND ((x.status = 'REFUNDED' AND x.complete = 1 AND x.release = 0)
                                           OR (x.status = 'FAILED' AND x.complete = 0 AND x.release = 1)
                                           OR (x.status = 'REFUNDING' AND x.complete = 0 AND x.release = 0)))""");
        checker.register("I4c.merchant-payable", "each merchant payable balance equals captured net of fees, minus the merchant share of refunds, minus payouts", """
                SELECT x.code || ' ledger ' || x.ledger || ' vs expected ' || x.expected
                  FROM (SELECT a.code, b.credit_total - b.debit_total AS ledger,
                               COALESCE((SELECT SUM(p.captured_amount - p.fee) FROM payments p WHERE 'merchant:' || p.merchant_id || ':payable' = a.code), 0)
                             - COALESCE((SELECT SUM(floor((p.refunded_amount + p.refund_reserved)::numeric * (p.amount - p.fee) / p.amount))
                                           FROM payments p WHERE 'merchant:' || p.merchant_id || ':payable' = a.code), 0)
                             - COALESCE((SELECT SUM(l.amount) FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
                                          WHERE l.account_id = a.id AND e.kind = 'PAYOUT' AND l.side = 'D'), 0) AS expected
                          FROM accounts a JOIN account_balances b ON b.account_id = a.id WHERE a.code LIKE 'merchant:%:payable') x
                 WHERE x.ledger <> x.expected""");
        checker.register("I7a.outbox", "every payment state change has exactly one outbox event", """
                SELECT 'payment ' || t.payment_id || ' ' || t.type || ' events=' || COALESCE(o.n, 0)
                  FROM (SELECT DISTINCT payment_id,
                               CASE to_status WHEN 'CREATED' THEN 'payment.created' WHEN 'AUTHORIZED' THEN 'payment.authorized'
                                              WHEN 'CAPTURED' THEN 'payment.captured' WHEN 'VOIDED' THEN 'payment.voided' WHEN 'FAILED' THEN 'payment.failed' END AS type
                          FROM payment_events WHERE to_status IN ('CREATED', 'AUTHORIZED', 'CAPTURED', 'VOIDED', 'FAILED')) t
                  LEFT JOIN (SELECT aggregate_id, type, count(*) AS n FROM outbox GROUP BY aggregate_id, type) o
                    ON o.aggregate_id = t.payment_id::text AND o.type = t.type
                 WHERE COALESCE(o.n, 0) <> 1
                UNION ALL
                SELECT 'payment ' || p.id || ' payment.refunded events=' || COALESCE(o.n, 0) || ' refunds done=' || r.n
                  FROM payments p JOIN (SELECT payment_id, count(*) AS n FROM refunds WHERE status = 'REFUNDED' GROUP BY payment_id) r ON r.payment_id = p.id
                  LEFT JOIN (SELECT aggregate_id, count(*) AS n FROM outbox WHERE type = 'payment.refunded' GROUP BY aggregate_id) o ON o.aggregate_id = p.id::text
                 WHERE COALESCE(o.n, 0) <> r.n
                UNION ALL
                SELECT 'outbox event for unknown payment ' || o.aggregate_id FROM outbox o
                 WHERE o.type LIKE 'payment.%' AND NOT EXISTS (SELECT 1 FROM payments p WHERE p.id::text = o.aggregate_id)""");
    }
}
