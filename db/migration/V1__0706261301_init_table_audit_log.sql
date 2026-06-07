CREATE TABLE audit.audit_logs (
                                  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                  trace_id        VARCHAR(100),
                                  user_id         UUID,
                                  transaction_id  UUID,
                                  service         VARCHAR(50) NOT NULL,
                                  action          VARCHAR(100) NOT NULL,
                                  status          VARCHAR(50),
                                  request_payload JSONB,
                                  response_payload JSONB,
                                  error_message   TEXT,
                                  duration_ms     BIGINT,
                                  ip_address      VARCHAR(45),
                                  created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_logs_trace_id ON audit.audit_logs(trace_id);
CREATE INDEX idx_audit_logs_user_id ON audit.audit_logs(user_id);
CREATE INDEX idx_audit_logs_transaction_id ON audit.audit_logs(transaction_id);
CREATE INDEX idx_audit_logs_created_at ON audit.audit_logs(created_at DESC);
