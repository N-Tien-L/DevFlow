-- DevFlow T-009: Audit trail of account deletions (GDPR right to erasure); auth module owns this table.
-- Architecture Spec: docs/ARCHITECTURE.md Section 4 (user.account_deleted), docs/DATABASE_DESIGN.md Section 3.1

-- Records THAT an account was erased, without keeping who it was: no email, no name, and the client IP is
-- stored truncated (IPv4 /24, IPv6 /48). user_id is the id of the erased account and deliberately has no
-- foreign key, because the users row no longer exists.
CREATE TABLE account_deletion_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    deleted_at TIMESTAMPTZ NOT NULL,
    client_ip_masked VARCHAR(45),
    workspaces_deleted INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_account_deletion_audit_user UNIQUE (user_id),
    CONSTRAINT ck_account_deletion_audit_workspaces CHECK (workspaces_deleted >= 0)
);
