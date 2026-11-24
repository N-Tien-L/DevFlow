-- Board-owned stable workspace namespace. Preflight aborts before changing data.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM tasks t JOIN columns c ON c.id=t.column_id
               JOIN boards b ON b.id=c.board_id GROUP BY b.workspace_id HAVING count(*) > 9999) THEN
        RAISE EXCEPTION 'TASK_KEY_RANGE_EXHAUSTED';
    END IF;
END $$;

CREATE TABLE board_task_key_counters (
    workspace_id UUID PRIMARY KEY,
    last_number INT NOT NULL CHECK (last_number BETWEEN 1 AND 9999)
);
ALTER TABLE tasks ADD COLUMN key_workspace_id UUID;
ALTER TABLE tasks ADD COLUMN task_number INT;
WITH numbered AS (
    SELECT t.id, b.workspace_id, row_number() OVER
        (PARTITION BY b.workspace_id ORDER BY t.created_at, t.id)::INT AS number
    FROM tasks t JOIN columns c ON c.id=t.column_id JOIN boards b ON b.id=c.board_id
)
UPDATE tasks t SET key_workspace_id=n.workspace_id, task_number=n.number FROM numbered n WHERE n.id=t.id;
INSERT INTO board_task_key_counters SELECT key_workspace_id, max(task_number) FROM tasks GROUP BY key_workspace_id;
ALTER TABLE tasks ALTER COLUMN key_workspace_id SET NOT NULL;
ALTER TABLE tasks ALTER COLUMN task_number SET NOT NULL;
ALTER TABLE tasks ADD CONSTRAINT uq_tasks_workspace_number UNIQUE(key_workspace_id, task_number);
ALTER TABLE tasks ADD CONSTRAINT ck_tasks_number CHECK(task_number BETWEEN 1 AND 9999);

-- All insert paths (including imports) allocate atomically. Deleted numbers never return to the pool.
CREATE FUNCTION board_assign_task_key() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE workspace UUID; allocated INT;
BEGIN
    IF NEW.column_id IS NULL THEN RAISE EXCEPTION 'TASK_COLUMN_REQUIRED' USING ERRCODE='23502'; END IF;
    SELECT b.workspace_id INTO workspace FROM columns c JOIN boards b ON b.id=c.board_id WHERE c.id=NEW.column_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'TASK_COLUMN_NOT_FOUND' USING ERRCODE='23503'; END IF;
    IF TG_OP='INSERT' THEN
        INSERT INTO board_task_key_counters(workspace_id,last_number) VALUES(workspace,1)
        ON CONFLICT(workspace_id) DO UPDATE SET last_number=board_task_key_counters.last_number+1
            WHERE board_task_key_counters.last_number < 9999
        RETURNING last_number INTO allocated;
        IF allocated IS NULL THEN RAISE EXCEPTION 'TASK_KEY_RANGE_EXHAUSTED' USING ERRCODE='23514'; END IF;
        NEW.key_workspace_id := workspace;
        NEW.task_number := allocated;
    ELSIF NEW.key_workspace_id IS DISTINCT FROM OLD.key_workspace_id
          OR NEW.task_number IS DISTINCT FROM OLD.task_number OR workspace <> OLD.key_workspace_id THEN
        RAISE EXCEPTION 'TASK_KEY_IMMUTABLE' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER board_task_key BEFORE INSERT OR UPDATE ON tasks FOR EACH ROW EXECUTE FUNCTION board_assign_task_key();

-- Prevent indirect cross-workspace moves as well as direct task moves.
CREATE FUNCTION board_guard_key_parent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME='boards' THEN
        IF NEW.workspace_id IS DISTINCT FROM OLD.workspace_id THEN
            RAISE EXCEPTION 'BOARD_WORKSPACE_IMMUTABLE' USING ERRCODE='23514';
        END IF;
    ELSE
        IF NEW.board_id IS DISTINCT FROM OLD.board_id THEN
            RAISE EXCEPTION 'COLUMN_BOARD_IMMUTABLE' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER board_key_parent BEFORE UPDATE OF workspace_id ON boards FOR EACH ROW EXECUTE FUNCTION board_guard_key_parent();
CREATE TRIGGER column_key_parent BEFORE UPDATE OF board_id ON columns FOR EACH ROW EXECUTE FUNCTION board_guard_key_parent();
