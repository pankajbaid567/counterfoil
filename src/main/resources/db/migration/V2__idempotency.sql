-- Idempotency keys (ADR-006). A key row is written in the same transaction as the business rows for
-- ledger-only calls, so there is no half state. PSP-backed calls use IN_PROGRESS across two transactions.
CREATE TABLE idempotency_keys (
    scope           TEXT        NOT NULL,
    key             TEXT        NOT NULL CHECK (length(key) BETWEEN 1 AND 200),
    request_hash    TEXT        NOT NULL,
    state           TEXT        NOT NULL CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    response_status INT,
    response_body   TEXT,
    resource_type   TEXT,
    resource_id     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    PRIMARY KEY (scope, key),
    CHECK (state = 'IN_PROGRESS' OR (response_status IS NOT NULL AND response_body IS NOT NULL))
);
