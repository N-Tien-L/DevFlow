-- Metadata only: payloads, signatures and secrets must never be stored here.
CREATE TABLE github_webhook_receipts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repository_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    delivery_id VARCHAR(128) NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    event_name VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_github_receipt_delivery UNIQUE (repository_id, delivery_id),
    CONSTRAINT uq_github_receipt_payload UNIQUE (repository_id, payload_sha256),
    CONSTRAINT ck_github_receipt_delivery CHECK (delivery_id ~ '^[A-Za-z0-9-]{1,128}$'),
    CONSTRAINT ck_github_receipt_digest CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_github_receipt_event CHECK (event_name ~ '^[a-z0-9_]{1,64}$'),
    CONSTRAINT ck_github_receipt_status CHECK (
        (status = 'PROCESSING' AND completed_at IS NULL)
        OR (status = 'COMPLETED' AND completed_at IS NOT NULL)
    )
);

CREATE INDEX idx_github_receipt_created_at ON github_webhook_receipts(created_at);
