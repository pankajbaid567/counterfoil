-- Transactional outbox and receiver-side de-duplication (ADR-008, I7).
CREATE TABLE outbox (
    id              BIGSERIAL PRIMARY KEY,
    event_id        UUID        NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    type            TEXT        NOT NULL,
    aggregate_id    TEXT        NOT NULL,
    payload         TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DELIVERED', 'DEAD')),
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_until    TIMESTAMPTZ,
    delivered_at    TIMESTAMPTZ,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX outbox_due_idx ON outbox (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX outbox_aggregate_idx ON outbox (aggregate_id);

-- One row per event applied by a receiver. The primary key makes "apply once" a database fact.
CREATE TABLE inbound_events (
    source      TEXT        NOT NULL,
    event_id    TEXT        NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (source, event_id)
);
