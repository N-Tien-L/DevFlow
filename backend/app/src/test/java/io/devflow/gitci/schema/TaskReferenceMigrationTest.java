package io.devflow.gitci.schema;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Forward migration tests run old data at V13 then migrate the actual production scripts. */
class TaskReferenceMigrationTest extends GitCiPostgresSupport {
    @Test void stableBackfillCountersValidationAndLegacyAssociationConversion() {
        migration("13").migrate();
        UUID workspace=UUID.randomUUID();
        UUID board=jdbc.queryForObject("INSERT INTO boards(workspace_id,name) VALUES (?,'fixture') RETURNING id",UUID.class,workspace);
        UUID column=jdbc.queryForObject("INSERT INTO columns(board_id,name,position,status_category) VALUES (?,'fixture',0,'TODO') RETURNING id",UUID.class,board);
        UUID first=UUID.fromString("10000000-0000-0000-0000-000000000001"), second=UUID.fromString("20000000-0000-0000-0000-000000000001");
        for(UUID id:java.util.List.of(second,first)) jdbc.update("INSERT INTO tasks(id,column_id,title,position,created_at) VALUES (?,?,'untouched',0,'2026-01-01T00:00:00Z')",id,column);
        UUID repo=insert("repositories",row("repositories"));
        jdbc.update("INSERT INTO git_commits(repository_id,sha,message,task_id,committed_at) VALUES (?,?,'',?,CURRENT_TIMESTAMP)",repo,SHA,first);
        migration("15").migrate(); migration("15").validate();
        assertThat(jdbc.queryForList("SELECT task_number FROM tasks ORDER BY id",Integer.class)).containsExactly(1,2);
        assertThat(jdbc.queryForList("SELECT title FROM tasks",String.class)).containsOnly("untouched");
        assertThat(jdbc.queryForList("SELECT task_id FROM git_commit_task_links",UUID.class)).containsExactly(first);
        assertThat(jdbc.queryForObject("SELECT last_number FROM board_task_key_counters WHERE workspace_id=?",Integer.class,workspace)).isEqualTo(2);
        migration("15").migrate();
        assertThat(jdbc.queryForList("SELECT task_number FROM tasks ORDER BY id",Integer.class)).containsExactly(1,2);
        assertThatThrownBy(() -> jdbc.update("UPDATE boards SET workspace_id=? WHERE id=?",UUID.randomUUID(),board))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void oversizedWorkspaceAbortsMigrationWithoutPartialBackfill() {
        migration("13").migrate();
        UUID board=jdbc.queryForObject("INSERT INTO boards(workspace_id,name) VALUES (?,'fixture') RETURNING id",UUID.class,UUID.randomUUID());
        UUID column=jdbc.queryForObject("INSERT INTO columns(board_id,name,position,status_category) VALUES (?,'fixture',0,'TODO') RETURNING id",UUID.class,board);
        jdbc.update("INSERT INTO tasks(column_id,title,position) SELECT ?,'fixture',0 FROM generate_series(1,10000)",column);
        assertThatThrownBy(() -> migration("15").migrate()).hasMessageContaining("TASK_KEY_RANGE_EXHAUSTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tasks",Integer.class)).isEqualTo(10000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND table_name='tasks' AND column_name='task_number'",Integer.class,schema)).isZero();
    }
}
