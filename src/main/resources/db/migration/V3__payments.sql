-- Payment orchestration (ADR-007, ADR-009). The PSP simulator keeps its own committed state in psp_transactions.

CREATE TABLE payments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id     TEXT        NOT NULL CHECK (merchant_id ~ '^[a-z0-9_\-]{1,60}$'),
    amount          BIGINT      NOT NULL CHECK (amount > 0 AND amount <= 100000000000),
    currency        CHAR(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status          TEXT        NOT NULL CHECK (status IN ('CREATED', 'AUTHORIZING', 'AUTHORIZED', 'CAPTURING', 'CAPTURED',
                                                           'PARTIALLY_REFUNDED', 'REFUNDED', 'VOIDING', 'VOIDED', 'FAILED')),
    fee             BIGINT      NOT NULL DEFAULT 0 CHECK (fee >= 0),
    captured_amount BIGINT      NOT NULL DEFAULT 0,
    refunded_amount BIGINT      NOT NULL DEFAULT 0,
    refund_reserved BIGINT      NOT NULL DEFAULT 0,
    failure_reason  TEXT,
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- I4: refunds (done or reserved) can never exceed what was captured, and capture never exceeds the amount
    CONSTRAINT captured_within_amount CHECK (captured_amount >= 0 AND captured_amount <= amount),
    CONSTRAINT refunds_within_capture CHECK (refunded_amount >= 0 AND refund_reserved >= 0 AND refunded_amount + refund_reserved <= captured_amount)
);

CREATE TABLE payment_events (
    payment_id  UUID        NOT NULL REFERENCES payments (id),
    seq         INT         NOT NULL,
    from_status TEXT,
    to_status   TEXT        NOT NULL,
    detail      TEXT,
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (payment_id, seq)
);

CREATE TABLE refunds (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id  UUID        NOT NULL REFERENCES payments (id),
    amount      BIGINT      NOT NULL CHECK (amount > 0),
    status      TEXT        NOT NULL CHECK (status IN ('REFUNDING', 'REFUNDED', 'FAILED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX refunds_payment_idx ON refunds (payment_id);

-- One row per PSP-backed operation. The partial unique index is the database-level guard that a payment is
-- authorized, captured and voided at most once, however many requests race (I4).
CREATE TABLE payment_ops (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id  UUID        NOT NULL REFERENCES payments (id),
    kind        TEXT        NOT NULL CHECK (kind IN ('AUTHORIZE', 'CAPTURE', 'VOID', 'REFUND')),
    refund_id   UUID REFERENCES refunds (id),
    amount      BIGINT      NOT NULL,
    psp_ref     TEXT        NOT NULL,
    idem_scope  TEXT        NOT NULL,
    idem_key    TEXT        NOT NULL,
    state       TEXT        NOT NULL CHECK (state IN ('PENDING', 'DONE')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX one_op_per_kind ON payment_ops (payment_id, kind) WHERE kind <> 'REFUND';
CREATE INDEX payment_ops_pending_idx ON payment_ops (created_at) WHERE state = 'PENDING';

-- The "remote" PSP's own state, committed independently of our transactions. Idempotent per (reference, kind).
CREATE TABLE psp_transactions (
    psp_ref    TEXT        NOT NULL,
    kind       TEXT        NOT NULL,
    amount     BIGINT      NOT NULL,
    outcome    TEXT        NOT NULL CHECK (outcome IN ('APPROVED', 'DECLINED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (psp_ref, kind)
);

-- A payment can have at most one capture entry in the ledger, enforced by the database (I4).
CREATE UNIQUE INDEX one_capture_entry_per_payment ON journal_entries (reference) WHERE kind = 'PAYMENT_CAPTURE';
