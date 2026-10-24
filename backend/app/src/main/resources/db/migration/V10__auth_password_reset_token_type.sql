-- DevFlow T-008: Forgot password & password reset (auth module).
-- Architecture Spec: docs/ARCHITECTURE.md Section 9.2, docs/DATABASE_DESIGN.md Section 3.1

-- 1. Password reset tokens reuse verification_tokens (single use, SHA-256 digest, short expiry).
ALTER TABLE verification_tokens DROP CONSTRAINT ck_verification_tokens_type;
ALTER TABLE verification_tokens ADD CONSTRAINT ck_verification_tokens_type
    CHECK (type IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET'));

-- 2. When the password was last changed. JWTs (access and refresh) issued before this instant are rejected,
-- which signs the user out everywhere after a reset. NULL for accounts that never changed their password.
ALTER TABLE users ADD COLUMN password_changed_at TIMESTAMPTZ;
