-- A reconciliation compares a bank statement with the ledger's bank:settlement account for a period.
-- statement_hash makes submitting the same statement again return the stored result, not a second run.
CREATE TABLE reconciliations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    statement_hash  TEXT        NOT NULL UNIQUE,
    period_from     TIMESTAMPTZ NOT NULL,
    period_to       TIMESTAMPTZ NOT NULL CHECK (period_to > period_from),
    bank_lines      INT         NOT NULL,
    ledger_refs     INT         NOT NULL,
    matched         INT         NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reconciliation_items (
    id                BIGSERIAL PRIMARY KEY,
    reconciliation_id UUID   NOT NULL REFERENCES reconciliations (id),
    category          TEXT   NOT NULL CHECK (category IN ('MISSING_IN_BANK', 'MISSING_IN_LEDGER', 'AMOUNT_MISMATCH', 'DUPLICATE_IN_BANK')),
    reference         TEXT   NOT NULL,
    ledger_amount     BIGINT,
    bank_amount       BIGINT,
    note              TEXT
);
CREATE INDEX reconciliation_items_rec_idx ON reconciliation_items (reconciliation_id);
