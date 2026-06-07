CREATE TABLE payment.transactions (
                                      id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                      idempotency_key     VARCHAR(100) NOT NULL UNIQUE,
                                      user_id             UUID NOT NULL,
                                      type                VARCHAR(15) NOT NULL,
                                      status              VARCHAR(15) NOT NULL DEFAULT 'PENDING',
                                      amount              NUMERIC(19,2) NOT NULL,
                                      description         VARCHAR(255),
                                      trace_id            VARCHAR(100),
                                      created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
                                      updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_transactions_user_id ON payment.transactions(user_id);
CREATE INDEX idx_transactions_idempotency_key ON payment.transactions(idempotency_key);
CREATE INDEX idx_transactions_status ON payment.transactions(status);
CREATE INDEX idx_transactions_created_at ON payment.transactions(created_at DESC);