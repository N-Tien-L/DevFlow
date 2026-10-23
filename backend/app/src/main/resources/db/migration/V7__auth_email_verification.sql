-- DevFlow T-004A: Account status lifecycle & email verification tokens (auth module owns these tables).
-- Architecture Spec: docs/ARCHITECTURE.md Section 9.2 (Email Verification), docs/diagrams/AUTH_ARCHITECTURE.md

-- 1. Account status on users.
-- Accounts created before this migration were never asked to verify, so they are backfilled as ACTIVE;
-- the default then switches so every new row starts unverified.
ALTER TABLE users ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE users ALTER COLUMN status SET DEFAULT 'PENDING_VERIFICATION';
ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMPTZ;
ALTER TABLE users ADD CONSTRAINT ck_users_status
    CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE'));

CREATE INDEX idx_users_status_created_at ON users(status, created_at);

-- 2. Single-use, expiring verification tokens.
-- Only the SHA-256 hex digest of the token is stored; the raw token exists solely in the emailed link.
CREATE TABLE verification_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    type VARCHAR(32) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_verification_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_verification_tokens_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ck_verification_tokens_type CHECK (type IN ('EMAIL_VERIFICATION'))
);

CREATE INDEX idx_verification_tokens_user_type ON verification_tokens(user_id, type);
CREATE INDEX idx_verification_tokens_expires_at ON verification_tokens(expires_at);
