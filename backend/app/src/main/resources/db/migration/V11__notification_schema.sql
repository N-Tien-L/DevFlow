-- Temporary migration number reserved for T-040A; reconcile with other lanes at merge.
CREATE TABLE notifications (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    event_id UUID NOT NULL,
    recipient_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    workspace_id UUID,
    type VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL CHECK (length(trim(title)) > 0),
    content VARCHAR(4000) NOT NULL,
    reference_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMPTZ,
    CONSTRAINT uq_notifications_event_recipient UNIQUE (event_id, recipient_id)
);
CREATE INDEX idx_notifications_recipient_read_created
    ON notifications (recipient_id, read_at, created_at DESC, id DESC);
CREATE INDEX idx_notifications_recipient_created
    ON notifications (recipient_id, created_at DESC, id DESC);
CREATE INDEX idx_notifications_retention ON notifications (created_at, id);
