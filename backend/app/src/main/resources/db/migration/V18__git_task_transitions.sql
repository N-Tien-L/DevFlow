-- GitCI snapshot: retries retain the original tenant and stable V17 event ID.
ALTER TABLE git_domain_event_outbox ADD COLUMN workspace_id UUID;
UPDATE git_domain_event_outbox o SET workspace_id = r.workspace_id
FROM repositories r WHERE r.id = o.repository_id;
ALTER TABLE git_domain_event_outbox ALTER COLUMN workspace_id SET NOT NULL;
CREATE INDEX ix_git_outbox_claim_token ON git_domain_event_outbox(claim_token) WHERE status = 'PROCESSING';

-- Board owns the manual watermark and receipts; no cross-module foreign keys.
ALTER TABLE tasks ADD COLUMN last_manual_status_changed_at TIMESTAMPTZ;
UPDATE tasks SET last_manual_status_changed_at = updated_at;

CREATE TABLE board_git_event_receipts (
    event_id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    repository_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN ('git.commit_linked','git.pr_opened','git.pr_merged')),
    fingerprint VARCHAR(64) NOT NULL,
    outcome VARCHAR(32) NOT NULL CHECK (outcome IN (
        'APPLIED','NOOP','STALE_MANUAL','ARCHIVED','SKIPPED_TARGET_MISSING','SKIPPED_TARGET_AMBIGUOUS'
    )),
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_board_git_receipt_task ON board_git_event_receipts(task_id);

ALTER TABLE board_audit_events
    ADD COLUMN cause VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN source_event_id UUID,
    ALTER COLUMN actor_id DROP NOT NULL;
ALTER TABLE board_audit_events ADD CONSTRAINT ck_board_audit_cause_actor CHECK (
    (cause = 'MANUAL' AND actor_id IS NOT NULL AND source_event_id IS NULL)
    OR (cause = 'GIT' AND action = 'MOVE' AND resource_type = 'TASK'
        AND actor_id IS NULL AND source_event_id IS NOT NULL)
);
