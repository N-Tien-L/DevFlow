-- DevFlow T-008: Forgot password & password reset (auth module).
-- Architecture Spec: docs/ARCHITECTURE.md Section 9.2, docs/DATABASE_DESIGN.md Section 3.1

-- 1. Password reset tokens reuse verification_tokens (single use, SHA-256 digest, short expiry).
ALTER TABLE verification_tokens DROP CONSTRAINT ck_verification_tokens_type;
ALTER TABLE verification_tokens ADD CONSTRAINT ck_verification_tokens_type
    CHECK (type IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET'));

-- At most one usable reset link per user: issuing a new one invalidates the previous one (used_at is set),
-- and this index makes concurrent requests unable to leave two links open.
CREATE UNIQUE INDEX uq_verification_tokens_open_password_reset
    ON verification_tokens(user_id) WHERE type = 'PASSWORD_RESET' AND used_at IS NULL;

-- 2. Session revocation. Every access and refresh JWT carries the user's token_version (claim "tv"); a password
-- reset increments it, so every token issued before the reset stops matching, whatever its issue time.
ALTER TABLE users ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE users ADD CONSTRAINT ck_users_token_version CHECK (token_version >= 0);

-- When the password was last changed (shown to the user, kept for support). NULL if it never changed.
ALTER TABLE users ADD COLUMN password_changed_at TIMESTAMPTZ;
