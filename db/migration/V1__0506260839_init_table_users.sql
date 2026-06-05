CREATE TABLE wallet.users (
                       id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                       username    VARCHAR(50) NOT NULL UNIQUE,
                       email       VARCHAR(100) NOT NULL UNIQUE,
                       password    VARCHAR(255) NOT NULL,
                       is_active   BOOLEAN NOT NULL DEFAULT TRUE,
                       created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
                       updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_username ON wallet.users(username);
CREATE INDEX idx_users_email ON wallet.users(email);