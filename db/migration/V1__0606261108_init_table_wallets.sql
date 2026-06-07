CREATE TABLE wallet.wallets (
                         id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                         user_id     UUID NOT NULL UNIQUE,
                         balance     NUMERIC(19,2) NOT NULL DEFAULT 0.00,
                         version     BIGINT NOT NULL DEFAULT 0,
                         created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
                         updated_at  TIMESTAMP NOT NULL DEFAULT NOW(),
                         CONSTRAINT chk_balance_non_negative CHECK (balance >= 0)
);

CREATE INDEX idx_wallets_user_id ON wallet.wallets(user_id);
