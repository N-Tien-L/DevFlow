-- DevFlow T-031: AI CI Failure Log Summaries.
-- Stores summarized CI failure logs for idempotency and quick retrieval (ARCHITECTURE.md Section 4, PRODUCT_SPEC.md Section 5.2a).

CREATE TABLE ai_ci_summaries (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pipeline_id      VARCHAR(255) NOT NULL UNIQUE,
    repo             VARCHAR(255),
    commit_sha       VARCHAR(64),
    summary_markdown TEXT NOT NULL,
    source           VARCHAR(20) NOT NULL DEFAULT 'LLM',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ai_ci_summaries_source CHECK (source IN ('LLM', 'FALLBACK'))
);

CREATE INDEX idx_ai_ci_summaries_pipeline ON ai_ci_summaries(pipeline_id);
