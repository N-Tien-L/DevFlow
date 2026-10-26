-- DevFlow T-009: account deletion. Section 1 (auth module): audit trail of account deletions (GDPR right to
-- erasure). Section 2 (board module): durable Board cleanup jobs.
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

-- DevFlow T-009 (board module owns this table): durable Board cleanup after an account or workspace deletion.
-- A row is written in the same transaction that deletes the user, so the cleanup cannot be lost; the board
-- module deletes it once the cleanup committed, and retries due rows on a schedule with exponential backoff.
-- subject_id / lock_user_id are soft references (the users / workspaces rows are already gone).
CREATE TABLE board_account_cleanup_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind VARCHAR(16) NOT NULL,
    subject_id UUID NOT NULL,
    lock_user_id UUID NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_board_account_cleanup_jobs_subject UNIQUE (kind, subject_id),
    CONSTRAINT ck_board_account_cleanup_jobs_kind CHECK (kind IN ('USER', 'WORKSPACE')),
    CONSTRAINT ck_board_account_cleanup_jobs_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_board_account_cleanup_jobs_due ON board_account_cleanup_jobs(next_attempt_at);
