-- T-020: Git/CI-owned persistence. V3-V5 are already allocated to Board migrations.
-- Workspace/task UUIDs are soft references; only Git/CI parent-child links use FKs.
-- Secret values live in deployment configuration, never in these tables.

CREATE TABLE repositories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL,
    provider VARCHAR(50) NOT NULL,
    remote_repo_id VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    repository_url VARCHAR(2048) NOT NULL,
    webhook_secret_ref VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_repositories_workspace_provider_remote UNIQUE (workspace_id, provider, remote_repo_id),
    CONSTRAINT ck_repositories_provider CHECK (provider IN ('GITHUB', 'GITLAB')),
    CONSTRAINT ck_repositories_remote_not_blank CHECK (btrim(remote_repo_id) <> ''),
    CONSTRAINT ck_repositories_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_repositories_url_not_blank CHECK (btrim(repository_url) <> ''),
    CONSTRAINT ck_repositories_secret_ref_not_blank CHECK (btrim(webhook_secret_ref) <> '')
);

CREATE TABLE git_commits (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repository_id UUID NOT NULL,
    sha VARCHAR(64) NOT NULL,
    message TEXT NOT NULL,
    author_name VARCHAR(255),
    author_email VARCHAR(255),
    task_id UUID,
    committed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_git_commits_repository FOREIGN KEY (repository_id) REFERENCES repositories(id) ON DELETE CASCADE,
    CONSTRAINT uq_git_commits_repo_sha UNIQUE (repository_id, sha),
    CONSTRAINT ck_git_commits_sha CHECK (sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$' AND sha !~ '^0+$'),
    CONSTRAINT ck_git_commits_message_size CHECK (octet_length(message) <= 65536),
    CONSTRAINT ck_git_commits_author_name CHECK (author_name IS NULL OR btrim(author_name) <> ''),
    CONSTRAINT ck_git_commits_author_email CHECK (author_email IS NULL OR btrim(author_email) <> '')
);

CREATE INDEX idx_git_commits_task_id ON git_commits(task_id);

CREATE TABLE pull_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repository_id UUID NOT NULL,
    pr_number INT NOT NULL,
    title VARCHAR(255) NOT NULL,
    source_branch VARCHAR(255) NOT NULL,
    target_branch VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    task_id UUID,
    pr_url VARCHAR(512) NOT NULL,
    provider_updated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_pull_requests_repository FOREIGN KEY (repository_id) REFERENCES repositories(id) ON DELETE CASCADE,
    CONSTRAINT uq_pr_repo_number UNIQUE (repository_id, pr_number),
    CONSTRAINT ck_pull_requests_number CHECK (pr_number > 0),
    CONSTRAINT ck_pull_requests_status CHECK (status IN ('OPEN', 'MERGED', 'CLOSED')),
    CONSTRAINT ck_pull_requests_title CHECK (btrim(title) <> ''),
    CONSTRAINT ck_pull_requests_source_branch CHECK (btrim(source_branch) <> ''),
    CONSTRAINT ck_pull_requests_target_branch CHECK (btrim(target_branch) <> ''),
    CONSTRAINT ck_pull_requests_url CHECK (btrim(pr_url) <> '')
);

CREATE INDEX idx_pull_requests_task_id ON pull_requests(task_id);
CREATE INDEX idx_pull_requests_repo_status ON pull_requests(repository_id, status);

-- One row per provider run attempt. CI may arrive before its push/commit webhook,
-- so commit_sha deliberately has no FK to git_commits.
CREATE TABLE ci_pipelines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repository_id UUID NOT NULL,
    pipeline_id VARCHAR(100) NOT NULL,
    run_attempt INT NOT NULL DEFAULT 1,
    commit_sha VARCHAR(64) NOT NULL,
    branch VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    provider_status VARCHAR(50),
    provider_conclusion VARCHAR(50),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    provider_updated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ci_pipelines_repository FOREIGN KEY (repository_id) REFERENCES repositories(id) ON DELETE CASCADE,
    CONSTRAINT uq_ci_pipelines_repo_run_attempt UNIQUE (repository_id, pipeline_id, run_attempt),
    CONSTRAINT ck_ci_pipelines_run_id CHECK (btrim(pipeline_id) <> ''),
    CONSTRAINT ck_ci_pipelines_attempt CHECK (run_attempt > 0),
    CONSTRAINT ck_ci_pipelines_sha CHECK (commit_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$' AND commit_sha !~ '^0+$'),
    CONSTRAINT ck_ci_pipelines_branch CHECK (btrim(branch) <> ''),
    CONSTRAINT ck_ci_pipelines_status CHECK (status IN (
        'PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED', 'SKIPPED',
        'TIMED_OUT', 'ACTION_REQUIRED', 'NEUTRAL', 'STALE', 'UNKNOWN'
    )),
    CONSTRAINT ck_ci_pipelines_time_order CHECK (
        started_at IS NULL OR finished_at IS NULL OR finished_at >= started_at
    )
);

CREATE INDEX idx_ci_pipelines_repo_status ON ci_pipelines(repository_id, status);
CREATE INDEX idx_ci_pipelines_repo_commit ON ci_pipelines(repository_id, commit_sha);

CREATE TABLE ci_failures (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pipeline_id UUID NOT NULL,
    failure_key VARCHAR(200) NOT NULL,
    stage_name VARCHAR(100) NOT NULL,
    job_name VARCHAR(100) NOT NULL,
    error_summary TEXT,
    raw_log_snippet TEXT,
    raw_log_reference VARCHAR(2048),
    ai_summary TEXT,
    related_task_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ci_failures_pipeline FOREIGN KEY (pipeline_id) REFERENCES ci_pipelines(id) ON DELETE CASCADE,
    CONSTRAINT uq_ci_failures_pipeline_key UNIQUE (pipeline_id, failure_key),
    CONSTRAINT ck_ci_failures_key CHECK (btrim(failure_key) <> ''),
    CONSTRAINT ck_ci_failures_stage CHECK (btrim(stage_name) <> ''),
    CONSTRAINT ck_ci_failures_job CHECK (btrim(job_name) <> ''),
    CONSTRAINT ck_ci_failures_error_size CHECK (octet_length(error_summary) <= 8192),
    CONSTRAINT ck_ci_failures_snippet_size CHECK (octet_length(raw_log_snippet) <= 16384),
    CONSTRAINT ck_ci_failures_ai_summary_size CHECK (octet_length(ai_summary) <= 16384)
);

CREATE INDEX idx_ci_failures_related_task ON ci_failures(related_task_id);

COMMENT ON COLUMN repositories.webhook_secret_ref IS
    'Opaque reference to deployment-managed key material; not a plaintext secret or arbitrary URL/file path.';
COMMENT ON COLUMN ci_pipelines.pipeline_id IS 'External provider run ID, scoped by repository and run_attempt.';
COMMENT ON COLUMN ci_failures.pipeline_id IS 'Internal UUID of a ci_pipelines attempt, not its external pipeline_id.';
COMMENT ON COLUMN ci_failures.raw_log_snippet IS 'Bounded snippet; ingestion must redact secrets before persistence.';
COMMENT ON COLUMN ci_failures.ai_summary IS 'GitCI-owned projection; AI supplies updates through domain events.';
