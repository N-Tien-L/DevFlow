package io.devflow.gitci.schema;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GitDomainEventMigrationTest extends GitCiPostgresSupport {
    @Test void v17BackfillsPendingTenantAndManualWatermarkAndEnforcesSystemAudit() {
        migration("16").migrate();
        UUID repo=insert("repositories",row("repositories"));
        UUID workspace=jdbc.queryForObject("SELECT workspace_id FROM repositories WHERE id=?",UUID.class,repo);
        UUID board=jdbc.queryForObject("INSERT INTO boards(workspace_id,name) VALUES (?,'fixture') RETURNING id",UUID.class,workspace);
        UUID column=jdbc.queryForObject("INSERT INTO columns(board_id,name,position,status_category) VALUES (?,'fixture',0,'TODO') RETURNING id",UUID.class,board);
        UUID task=jdbc.queryForObject("INSERT INTO tasks(column_id,title,position) VALUES (?,'fixture',0) RETURNING id",UUID.class,column);
        UUID event=UUID.randomUUID();
        jdbc.update("INSERT INTO git_domain_event_outbox(id,repository_id,task_id,event_type,git_object_key,repo,occurred_at) VALUES (?,?,?,'git.commit_linked',?,'test/repo',CURRENT_TIMESTAMP)",event,repo,task,SHA);
        var updated=jdbc.queryForObject("SELECT updated_at FROM tasks WHERE id=?",java.sql.Timestamp.class,task);
        migration("17").migrate(); migration("17").validate();
        assertThat(jdbc.queryForObject("SELECT workspace_id FROM git_domain_event_outbox WHERE id=?",UUID.class,event)).isEqualTo(workspace);
        assertThat(jdbc.queryForObject("SELECT last_manual_status_changed_at FROM tasks WHERE id=?",java.sql.Timestamp.class,task)).isEqualTo(updated);
        UUID fresh=jdbc.queryForObject("INSERT INTO tasks(column_id,title,position) VALUES (?,'new',1) RETURNING id",UUID.class,column);
        assertThat(jdbc.queryForObject("SELECT last_manual_status_changed_at FROM tasks WHERE id=?",java.sql.Timestamp.class,fresh)).isNull();
        String audit="INSERT INTO board_audit_events(actor_id,action,resource_type,resource_id,workspace_id,correlation_id,changed_fields,source_column_id,destination_column_id,cause,source_event_id) VALUES (?,'MOVE','TASK',?,?,?,'[]',?,?,?,?)";
        assertThatThrownBy(() -> jdbc.update(audit,null,task,workspace,UUID.randomUUID(),column,column,"MANUAL",null)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update(audit,null,task,workspace,UUID.randomUUID(),column,column,"GIT",event);
        assertThatThrownBy(() -> jdbc.update(audit,UUID.randomUUID(),task,workspace,UUID.randomUUID(),column,column,"GIT",event)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO board_git_event_receipts(event_id,task_id,repository_id,workspace_id,event_type,fingerprint,outcome,received_at) VALUES (?,?,?,?,'git.commit_linked',?,'APPLIED',CURRENT_TIMESTAMP)",event,task,repo,workspace,"a".repeat(64));
        jdbc.update("DELETE FROM boards WHERE id=?",board);
        assertThat(count("board_git_event_receipts")).isZero();
    }

    @Test void cleanInstallHasEmptyOutboxWithConstraintsAndCascade() {
        migration("17").migrate(); migration("17").validate();
        assertThat(count("git_domain_event_outbox")).isZero();
        UUID repo=insert("repositories",row("repositories")), task=UUID.randomUUID();
        jdbc.update("INSERT INTO git_domain_event_outbox(id,repository_id,task_id,event_type,git_object_key,repo,occurred_at) VALUES (?,?,?,'git.commit_linked',?,'test/repo',CURRENT_TIMESTAMP)",UUID.randomUUID(),repo,task,SHA);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO git_domain_event_outbox(id,repository_id,task_id,event_type,git_object_key,repo,occurred_at) VALUES (?,?,?,'git.commit_linked',?,'test/repo',CURRENT_TIMESTAMP)",UUID.randomUUID(),repo,task,SHA))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE git_domain_event_outbox SET status='PROCESSING'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE git_domain_event_outbox SET event_type='untrusted'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM repositories WHERE id=?",repo);
        assertThat(count("git_domain_event_outbox")).isZero();
    }

    @Test void upgradingV16PreservesLegacyRowsAndDoesNotBackfillEvents() {
        migration("16").migrate();
        UUID repo=insert("repositories",row("repositories")), task=UUID.randomUUID();
        jdbc.update("INSERT INTO git_commits(repository_id,sha,message,task_id,committed_at) VALUES (?,?,'legacy',?,CURRENT_TIMESTAMP)",repo,SHA,task);
        UUID commit=jdbc.queryForObject("SELECT id FROM git_commits",UUID.class);
        jdbc.update("INSERT INTO git_commit_task_links(commit_id,task_id,source_kind) VALUES (?,?,'MESSAGE')",commit,task);
        var pr=row("pull_requests"); pr.put("repository_id",repo); pr.put("task_id",task);
        insert("pull_requests",pr);
        jdbc.update("INSERT INTO github_webhook_receipts(repository_id,delivery_id,payload_sha256,event_name,status,completed_at) VALUES (?,'legacy',?,'push','COMPLETED',CURRENT_TIMESTAMP)",repo,"b".repeat(64));
        migration("17").migrate(); migration("17").validate();
        assertThat(count("git_domain_event_outbox")).isZero();
        assertThat(count("git_commits")).isEqualTo(1); assertThat(count("pull_requests")).isEqualTo(1);
        assertThat(count("github_webhook_receipts")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT task_id FROM git_commit_task_links",UUID.class)).containsExactly(task);
        assertThat(jdbc.queryForObject("SELECT message FROM git_commits",String.class)).isEqualTo("legacy");
    }
}
