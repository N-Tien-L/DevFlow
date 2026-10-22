-- T-013: extend the Board-owned audit trail to cover task lifecycle and moves.
-- Column references remain soft UUIDs so this table does not alter task/column cascades.

ALTER TABLE board_audit_events
    DROP CONSTRAINT ck_board_audit_action,
    DROP CONSTRAINT ck_board_audit_resource_type;

ALTER TABLE board_audit_events
    ADD CONSTRAINT ck_board_audit_action
        CHECK (action IN ('CREATE', 'UPDATE', 'ARCHIVE', 'RESTORE', 'DELETE', 'REORDER', 'MOVE')),
    ADD CONSTRAINT ck_board_audit_resource_type
        CHECK (resource_type IN ('BOARD', 'COLUMN', 'TASK')),
    ADD COLUMN source_column_id UUID,
    ADD COLUMN destination_column_id UUID;

ALTER TABLE board_audit_events
    ADD CONSTRAINT ck_board_audit_task_move_columns
        CHECK ((action = 'MOVE' AND resource_type = 'TASK'
                    AND source_column_id IS NOT NULL AND destination_column_id IS NOT NULL)
            OR (action <> 'MOVE' AND source_column_id IS NULL AND destination_column_id IS NULL));
