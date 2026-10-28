-- Hardening: DB-level money invariants, per-user idempotency, case-insensitive identity,
-- query-shaped indexes, removal of indexes that duplicate UNIQUE constraints.
-- Runs automatically only on an empty postgres volume (docker-entrypoint-initdb.d).
-- Existing volume: docker exec -i postgres psql -U "$DB_USER" -d "$DB_NAME" < db/migration/V1__0710261200_hardening_constraints.sql

-- ---------- auth ----------
-- Identity is stored lowercase by auth-service; normalize pre-existing rows first
UPDATE auth.users SET username = lower(username), email = lower(email)
WHERE username <> lower(username) OR email <> lower(email);

DROP INDEX IF EXISTS auth.idx_users_username;              -- duplicates UNIQUE(username)
DROP INDEX IF EXISTS auth.idx_users_email;                 -- duplicates UNIQUE(email)
DROP INDEX IF EXISTS auth.idx_refresh_tokens_token_hash;   -- duplicates UNIQUE(token_hash)
CREATE UNIQUE INDEX uq_users_username_lower ON auth.users (lower(username));
CREATE UNIQUE INDEX uq_users_email_lower    ON auth.users (lower(email));
CREATE INDEX idx_refresh_tokens_expires_at  ON auth.refresh_tokens (expires_at);

-- ---------- wallet ----------
DROP INDEX IF EXISTS wallet.idx_wallets_user_id;           -- duplicates UNIQUE(user_id)

ALTER TABLE wallet.mutations
    ADD CONSTRAINT chk_mutations_amount_positive CHECK (amount > 0),
    ADD CONSTRAINT chk_mutations_type CHECK (type IN ('CREDIT', 'DEBIT')),
    -- Ledger invariant enforced by the DB, not only by Java
    ADD CONSTRAINT chk_mutations_balance_math CHECK (
        (type = 'CREDIT' AND balance_after = balance_before + amount) OR
        (type = 'DEBIT'  AND balance_after = balance_before - amount)
    ),
    -- DB-level idempotency: at most one CREDIT and one DEBIT per wallet per payment transaction
    ADD CONSTRAINT uq_mutations_wallet_ref_type UNIQUE (wallet_id, reference_id, type);

-- History query: WHERE wallet_id = ? ORDER BY created_at DESC
DROP INDEX IF EXISTS wallet.idx_mutations_wallet_id;
DROP INDEX IF EXISTS wallet.idx_mutations_created_at;
CREATE INDEX idx_mutations_wallet_created ON wallet.mutations (wallet_id, created_at DESC);

-- ---------- payment ----------
ALTER TABLE payment.transactions
    ADD CONSTRAINT chk_transactions_amount_positive CHECK (amount > 0),
    ADD CONSTRAINT chk_transactions_type   CHECK (type IN ('TOPUP', 'TRANSFER')),
    ADD CONSTRAINT chk_transactions_status CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'EXPIRED')),
    ADD COLUMN failure_reason VARCHAR(255);

-- Idempotency key is scoped per user, not global
ALTER TABLE payment.transactions DROP CONSTRAINT IF EXISTS transactions_idempotency_key_key;
ALTER TABLE payment.transactions ADD CONSTRAINT uq_transactions_user_idem UNIQUE (user_id, idempotency_key);
DROP INDEX IF EXISTS payment.idx_transactions_idempotency_key;
DROP INDEX IF EXISTS payment.idx_transactions_user_id;     -- covered by the composite below
DROP INDEX IF EXISTS payment.idx_transactions_status;      -- low selectivity
CREATE INDEX idx_transactions_user_created ON payment.transactions (user_id, created_at DESC);
-- Scheduler query: PENDING TOPUP older than X; partial index stays small
CREATE INDEX idx_transactions_pending_topup ON payment.transactions (created_at)
    WHERE status = 'PENDING' AND type = 'TOPUP';

DROP INDEX IF EXISTS payment.idx_topup_requests_transaction_id;  -- duplicates UNIQUE(transaction_id)
DROP INDEX IF EXISTS payment.idx_topup_requests_gateway_ref;
CREATE UNIQUE INDEX uq_topup_requests_gateway_ref ON payment.topup_requests (gateway_ref);

ALTER TABLE payment.transfer_requests
    ADD CONSTRAINT chk_transfer_not_self CHECK (from_user_id <> to_user_id);
