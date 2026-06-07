CREATE TABLE wallet.mutations (
                                  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                  wallet_id       UUID NOT NULL REFERENCES wallet.wallets(id),
                                  type            VARCHAR(20) NOT NULL,
                                  amount          NUMERIC(19,2) NOT NULL,
                                  balance_before  NUMERIC(19,2) NOT NULL,
                                  balance_after   NUMERIC(19,2) NOT NULL,
                                  reference_id    VARCHAR(100),
                                  description     VARCHAR(255),
                                  created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_mutations_wallet_id ON wallet.mutations(wallet_id);
CREATE INDEX IF NOT EXISTS idx_mutations_created_at ON wallet.mutations(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_mutations_reference_id ON wallet.mutations(reference_id);
