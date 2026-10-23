-- T-017: reject stale full task updates instead of silently overwriting newer edits.
ALTER TABLE tasks
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
