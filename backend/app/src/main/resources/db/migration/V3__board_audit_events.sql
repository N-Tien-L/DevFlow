-- T-012: Durable, privacy-conscious audit records for Board and Column mutations.
-- Actor and workspace UUIDs are soft references because Auth owns those tables.

CREATE TABLE board_audit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id UUID NOT NULL,
    action VARCHAR(32) NOT NULL,
    resource_type VARCHAR(16) NOT NULL,
    resource_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    client_ip VARCHAR(45),
    changed_fields TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_board_audit_action CHECK (action IN ('CREATE', 'UPDATE', 'ARCHIVE', 'RESTORE', 'DELETE', 'REORDER')),
    CONSTRAINT ck_board_audit_resource_type CHECK (resource_type IN ('BOARD', 'COLUMN'))
);

CREATE INDEX idx_board_audit_workspace_created ON board_audit_events(workspace_id, created_at DESC);
CREATE INDEX idx_board_audit_resource_created ON board_audit_events(resource_type, resource_id, created_at DESC);
