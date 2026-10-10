-- Transactional outbox: written in the same DB transaction as the status change, relayed by payment-service.
-- Existing volume: docker exec -i postgres psql -U "$DB_USER" -d "$DB_NAME" < db/migration/V1__1010261002_init_table_outbox_events.sql
CREATE TABLE payment.outbox_events (
    id              UUID PRIMARY KEY,             -- also the eventId sent downstream
    aggregate_id    UUID NOT NULL,                -- payment.transactions.id
    event_type      VARCHAR(50) NOT NULL,
    payload         JSONB NOT NULL,
    status          VARCHAR(15) NOT NULL DEFAULT 'PENDING',
    attempts        INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL,
    last_error      VARCHAR(500),
    trace_id        VARCHAR(100),
    created_at      TIMESTAMP NOT NULL,
    published_at    TIMESTAMP,
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'DEAD')),
    CONSTRAINT chk_outbox_attempts CHECK (attempts >= 0),
    -- One event per type per transaction, even if a success path runs twice
    CONSTRAINT uq_outbox_aggregate_event UNIQUE (aggregate_id, event_type)
);

-- Relay query: PENDING and due, oldest first; partial index stays tiny once events are published
CREATE INDEX idx_outbox_events_due ON payment.outbox_events (next_attempt_at) WHERE status = 'PENDING';
