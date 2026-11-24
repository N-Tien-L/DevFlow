-- GitCI owns this queue. Task IDs are soft references; deletion is coordinated by typed events.
-- No backfill: historical Git rows must not produce historical notifications.
CREATE TABLE git_domain_event_outbox (
    id UUID PRIMARY KEY,
    repository_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    task_id UUID NOT NULL,
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN ('git.commit_linked','git.pr_opened','git.pr_merged')),
    git_object_key VARCHAR(64) NOT NULL,
    repo VARCHAR(512) NOT NULL,
    pr_url VARCHAR(1024),
    author VARCHAR(39),
    occurred_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PROCESSING','PUBLISHED','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    CONSTRAINT uq_git_domain_event UNIQUE(repository_id,event_type,git_object_key,task_id),
    CHECK ((status = 'PROCESSING') = (claim_token IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL)),
    CHECK ((event_type = 'git.commit_linked' AND pr_url IS NULL) OR
           (event_type IN ('git.pr_opened','git.pr_merged') AND pr_url IS NOT NULL AND author IS NULL))
);
CREATE INDEX ix_git_outbox_pending ON git_domain_event_outbox(next_attempt_at,created_at) WHERE status='PENDING';
CREATE INDEX ix_git_outbox_lease ON git_domain_event_outbox(lease_until) WHERE status='PROCESSING';
CREATE INDEX ix_git_outbox_published ON git_domain_event_outbox(published_at) WHERE status='PUBLISHED';
CREATE INDEX ix_git_outbox_task ON git_domain_event_outbox(task_id);
