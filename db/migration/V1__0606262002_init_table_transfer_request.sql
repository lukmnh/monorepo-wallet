CREATE TABLE payment.transfer_requests (
                                           id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                           transaction_id      UUID NOT NULL UNIQUE REFERENCES payment.transactions(id),
                                           from_user_id        UUID NOT NULL,
                                           to_user_id          UUID NOT NULL,
                                           created_at          TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_transfer_requests_from_user_id ON payment.transfer_requests(from_user_id);
CREATE INDEX idx_transfer_requests_to_user_id ON payment.transfer_requests(to_user_id);