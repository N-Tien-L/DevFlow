-- T-026: Store optional validated PR head SHA so CI runs triggered on a PR head commit
-- link accurately to tasks associated with that PR without joining on mutable branch names.
ALTER TABLE pull_requests
    ADD COLUMN head_sha VARCHAR(64),
    ADD CONSTRAINT ck_pull_requests_head_sha CHECK (
        head_sha IS NULL OR (head_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$' AND head_sha !~ '^0+$')
    );

CREATE INDEX idx_pull_requests_repo_head_sha
    ON pull_requests(repository_id, head_sha)
    WHERE head_sha IS NOT NULL;
