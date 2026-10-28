-- Reconciler query: PENDING TRANSFER older than X (unknown wallet-service outcome).
-- Partial index stays tiny: almost every transfer leaves PENDING within the request.
-- Existing volume: docker exec -i postgres psql -U "$DB_USER" -d "$DB_NAME" < db/migration/V1__0710261500_pending_transfer_index.sql
CREATE INDEX IF NOT EXISTS idx_transactions_pending_transfer ON payment.transactions (created_at)
    WHERE status = 'PENDING' AND type = 'TRANSFER';
