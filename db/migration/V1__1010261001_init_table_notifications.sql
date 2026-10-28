-- In-app notification inbox (one row per recipient).
-- Existing volume: docker exec -i postgres psql -U "$DB_USER" -d "$DB_NAME" < db/migration/V1__1010261001_init_table_notifications.sql
CREATE TABLE notification.notifications (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id       UUID NOT NULL,                 -- payment.outbox_events.id (correlation only)
    user_id        UUID NOT NULL,                 -- recipient
    type           VARCHAR(30) NOT NULL,
    title          VARCHAR(100) NOT NULL,
    body           VARCHAR(255) NOT NULL,
    amount         NUMERIC(19,2) NOT NULL,
    currency       VARCHAR(3) NOT NULL,
    transaction_id UUID NOT NULL,                 -- payment.transactions.id, deep link target
    is_read        BOOLEAN NOT NULL DEFAULT FALSE,
    read_at        TIMESTAMP,
    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_notifications_type CHECK (type IN ('TOPUP_SUCCESS', 'TRANSFER_SENT', 'TRANSFER_RECEIVED')),
    CONSTRAINT chk_notifications_amount_positive CHECK (amount > 0),
    -- Exactly-once per recipient: redelivered events (at-least-once outbox) become no-ops
    CONSTRAINT uq_notifications_txn_type UNIQUE (transaction_id, type)
);

-- Inbox query: WHERE user_id = ? ORDER BY created_at DESC
CREATE INDEX idx_notifications_user_created ON notification.notifications (user_id, created_at DESC);
-- Badge query: unread count per user; partial index stays small
CREATE INDEX idx_notifications_user_unread ON notification.notifications (user_id) WHERE is_read = FALSE;
