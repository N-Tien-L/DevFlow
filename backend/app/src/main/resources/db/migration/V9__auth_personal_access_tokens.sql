-- DevFlow T-038: Personal access tokens for MCP clients (auth module owns this table).
-- Architecture Spec: docs/ARCHITECTURE.md Section 5 (MCP server), docs/DATABASE_DESIGN.md Section 3.1

-- Long-lived, revocable tokens that AI coding agents (Cursor, Claude Code) present to the MCP endpoints.
-- The raw token is shown once at creation; only its SHA-256 hex digest is stored. token_prefix
-- ("dfp_" + 12 random hex chars) is the public lookup key, also shown in listings to identify a token.
CREATE TABLE personal_access_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    token_prefix VARCHAR(16) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_personal_access_tokens_prefix UNIQUE (token_prefix),
    CONSTRAINT uq_personal_access_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_personal_access_tokens_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ck_personal_access_tokens_name CHECK (char_length(btrim(name)) BETWEEN 1 AND 100),
    CONSTRAINT ck_personal_access_tokens_prefix CHECK (token_prefix ~ '^dfp_[0-9a-f]{12}$'),
    CONSTRAINT ck_personal_access_tokens_expiry CHECK (expires_at IS NULL OR expires_at > created_at)
);

-- Owner listing and the per-user quota of live (unrevoked) tokens.
CREATE INDEX idx_personal_access_tokens_user_live
    ON personal_access_tokens(user_id) WHERE revoked_at IS NULL;
