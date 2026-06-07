CREATE TABLE payment.topup_requests (
                                        id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                        transaction_id  UUID NOT NULL UNIQUE REFERENCES payment.transactions(id),
                                        gateway_ref     VARCHAR(100),
                                        callback_status VARCHAR(50),
                                        webhook_received_at TIMESTAMP,
                                        expires_at      TIMESTAMP NOT NULL,
                                        created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_topup_requests_transaction_id ON payment.topup_requests(transaction_id);
CREATE INDEX idx_topup_requests_gateway_ref ON payment.topup_requests(gateway_ref);
CREATE INDEX idx_topup_requests_expires_at ON payment.topup_requests(expires_at);