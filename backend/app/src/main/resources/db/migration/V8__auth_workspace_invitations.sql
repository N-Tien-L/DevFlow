-- DevFlow T-007: Workspace member invitations (auth module owns this table).
-- Architecture Spec: docs/ARCHITECTURE.md Section 9.2, docs/DATABASE_DESIGN.md Section 3.1

-- Single-use, expiring invitations to join a workspace. Only the SHA-256 hex digest of the token is
-- stored; the raw token exists solely in the emailed link. The invitee email is kept in lower case so
-- matching against the accepting account is exact.
CREATE TABLE workspace_invitations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL,
    email VARCHAR(254) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'MEMBER',
    token_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    invited_by UUID,
    expires_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    accepted_by UUID,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_workspace_invitations_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_workspace_invitations_workspace
        FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
    CONSTRAINT fk_workspace_invitations_invited_by
        FOREIGN KEY (invited_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT fk_workspace_invitations_accepted_by
        FOREIGN KEY (accepted_by) REFERENCES users(id) ON DELETE SET NULL,
    -- OWNER is never granted by invitation; ownership only comes from creating the workspace.
    CONSTRAINT ck_workspace_invitations_role CHECK (role IN ('ADMIN', 'MEMBER')),
    CONSTRAINT ck_workspace_invitations_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED')),
    CONSTRAINT ck_workspace_invitations_email_lower CHECK (email = LOWER(email)),
    CONSTRAINT ck_workspace_invitations_accepted
        CHECK ((status = 'ACCEPTED') = (accepted_at IS NOT NULL)),
    CONSTRAINT ck_workspace_invitations_revoked
        CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL))
);

-- At most one open invitation per (workspace, email). Also serves the "pending invitations of a
-- workspace" listing.
CREATE UNIQUE INDEX uq_workspace_invitations_open
    ON workspace_invitations(workspace_id, email) WHERE status = 'PENDING';

-- Nightly purge of expired / revoked invitations.
CREATE INDEX idx_workspace_invitations_expires_at ON workspace_invitations(expires_at);
