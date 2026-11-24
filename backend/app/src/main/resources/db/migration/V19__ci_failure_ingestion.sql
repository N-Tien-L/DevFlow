ALTER TABLE ci_pipelines
    ALTER COLUMN branch DROP NOT NULL,
    DROP CONSTRAINT ck_ci_pipelines_branch,
    ADD CONSTRAINT ck_ci_pipelines_branch CHECK (branch IS NULL OR branch ~ '[^[:space:]]'),
    ADD COLUMN failure_event_enqueued BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE ci_failures
    ADD COLUMN log_status VARCHAR(24) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN log_error_code VARCHAR(48),
    ADD COLUMN log_ready_at TIMESTAMPTZ,
    ADD COLUMN log_expires_at TIMESTAMPTZ,
    ADD COLUMN log_truncated BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN log_bytes INTEGER,
    ADD COLUMN log_digest VARCHAR(64),
    ADD COLUMN log_event_id UUID UNIQUE,
    ADD CONSTRAINT uq_ci_failure_attempt_id UNIQUE (pipeline_id, id),
    ADD CONSTRAINT ck_ci_log_status CHECK (log_status IN (
        'LEGACY', 'PENDING', 'FETCHING', 'READY', 'RETRY_WAIT',
        'FAILED', 'PERMISSION_DENIED', 'UNAVAILABLE', 'EXPIRED'
    )),
    ADD CONSTRAINT ck_ci_log_error_code CHECK (log_error_code ~ '^[A-Z0-9_]+$'),
    ADD CONSTRAINT ck_ci_log_ready CHECK (log_status <> 'READY' OR (
        raw_log_snippet IS NOT NULL AND raw_log_snippet ~ '[^[:space:]]'
        AND raw_log_reference IS NOT NULL AND raw_log_reference = 'ci-log:' || id::text
        AND log_bytes IS NOT NULL AND log_bytes = octet_length(raw_log_snippet)
        AND log_digest IS NOT NULL AND log_digest ~ '^[0-9a-f]{64}$'
        AND log_ready_at IS NOT NULL AND log_expires_at IS NOT NULL AND log_expires_at > log_ready_at AND log_event_id IS NOT NULL
        AND log_error_code IS NULL
    ));

CREATE TABLE ci_log_fetch_jobs (
    pipeline_id UUID PRIMARY KEY,
    failure_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 8),
    not_found_attempts INTEGER NOT NULL DEFAULT 0 CHECK (not_found_attempts BETWEEN 0 AND 3),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    error_code VARCHAR(48) CHECK (error_code ~ '^[A-Z0-9_]+$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (pipeline_id, failure_id) REFERENCES ci_failures(pipeline_id, id) ON DELETE CASCADE,
    CHECK (status IN ('PENDING', 'PROCESSING', 'DONE', 'FAILED')),
    CHECK ((status = 'PROCESSING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'PROCESSING' AND claim_token IS NULL AND lease_until IS NULL))
);

CREATE INDEX idx_ci_log_fetch_pending ON ci_log_fetch_jobs(next_attempt_at, pipeline_id) WHERE status = 'PENDING';
CREATE INDEX idx_ci_log_fetch_leases ON ci_log_fetch_jobs(lease_until, pipeline_id) WHERE status = 'PROCESSING';
CREATE INDEX idx_ci_log_fetch_active ON ci_log_fetch_jobs(pipeline_id) WHERE status IN ('PENDING', 'PROCESSING');

CREATE TABLE ci_failure_event_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pipeline_id UUID NOT NULL UNIQUE,
    failure_id UUID NOT NULL,
    repository_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL,
    provider_run_id VARCHAR(100) NOT NULL CHECK (provider_run_id ~ '^[1-9][0-9]*$'),
    run_attempt INTEGER NOT NULL CHECK (run_attempt > 0),
    repo VARCHAR(255) NOT NULL,
    commit_sha VARCHAR(64) NOT NULL CHECK (commit_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$' AND commit_sha !~ '^0+$'),
    occurred_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 8),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (pipeline_id, failure_id) REFERENCES ci_failures(pipeline_id, id) ON DELETE CASCADE,
    CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED')),
    CHECK ((status = 'PROCESSING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'PROCESSING' AND claim_token IS NULL AND lease_until IS NULL)),
    CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
);

CREATE INDEX idx_ci_event_pending ON ci_failure_event_outbox(next_attempt_at, id) WHERE status = 'PENDING';
CREATE INDEX idx_ci_event_leases ON ci_failure_event_outbox(lease_until, id) WHERE status = 'PROCESSING';
CREATE INDEX idx_ci_event_published ON ci_failure_event_outbox(published_at, id) WHERE status = 'PUBLISHED';
CREATE INDEX idx_ci_event_active ON ci_failure_event_outbox(repository_id) WHERE status IN ('PENDING', 'PROCESSING');
CREATE INDEX idx_ci_log_expiry ON ci_failures(log_expires_at, id) WHERE log_status = 'READY';
