CREATE TABLE git_commit_task_links (
    commit_id UUID NOT NULL REFERENCES git_commits(id) ON DELETE CASCADE,
    task_id UUID NOT NULL,
    source_kind VARCHAR(16) NOT NULL CHECK(source_kind IN ('MESSAGE','BRANCH','LEGACY')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(commit_id, task_id)
);
CREATE INDEX idx_git_commit_links_task ON git_commit_task_links(task_id);
CREATE TABLE git_pull_request_task_links (
    pull_request_id UUID NOT NULL REFERENCES pull_requests(id) ON DELETE CASCADE,
    task_id UUID NOT NULL,
    source_kind VARCHAR(16) NOT NULL CHECK(source_kind IN ('TITLE','BRANCH','LEGACY')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(pull_request_id, task_id)
);
CREATE INDEX idx_git_pr_links_task ON git_pull_request_task_links(task_id);
-- The old scalar carried no provenance; do not invent MESSAGE/TITLE evidence for it.
INSERT INTO git_commit_task_links(commit_id,task_id,source_kind) SELECT id,task_id,'LEGACY' FROM git_commits WHERE task_id IS NOT NULL;
INSERT INTO git_pull_request_task_links(pull_request_id,task_id,source_kind) SELECT id,task_id,'LEGACY' FROM pull_requests WHERE task_id IS NOT NULL;
ALTER TABLE pull_requests ADD COLUMN source_fingerprint VARCHAR(64);
COMMENT ON COLUMN git_commits.task_id IS 'Compatibility mirror: populated only for exactly one association; link table is authoritative.';
COMMENT ON COLUMN pull_requests.task_id IS 'Compatibility mirror: populated only for exactly one association; link table is authoritative.';
