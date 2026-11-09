package io.devflow.gitci.schema;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GitDomainEventMigrationTest extends GitCiPostgresSupport {
    @Test void cleanInstallHasEmptyOutboxWithConstraintsAndCascade() {
        migration("16").migrate(); migration("16").validate();
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

    @Test void upgradingV15PreservesLegacyRowsAndDoesNotBackfillEvents() {
        migration("15").migrate();
        UUID repo=insert("repositories",row("repositories")), task=UUID.randomUUID();
        jdbc.update("INSERT INTO git_commits(repository_id,sha,message,task_id,committed_at) VALUES (?,?,'legacy',?,CURRENT_TIMESTAMP)",repo,SHA,task);
        UUID commit=jdbc.queryForObject("SELECT id FROM git_commits",UUID.class);
        jdbc.update("INSERT INTO git_commit_task_links(commit_id,task_id,source_kind) VALUES (?,?,'MESSAGE')",commit,task);
        var pr=row("pull_requests"); pr.put("repository_id",repo); pr.put("task_id",task);
        insert("pull_requests",pr);
        jdbc.update("INSERT INTO github_webhook_receipts(repository_id,delivery_id,payload_sha256,event_name,status,completed_at) VALUES (?,'legacy',?,'push','COMPLETED',CURRENT_TIMESTAMP)",repo,"b".repeat(64));
        migration("16").migrate(); migration("16").validate();
        assertThat(count("git_domain_event_outbox")).isZero();
        assertThat(count("git_commits")).isEqualTo(1); assertThat(count("pull_requests")).isEqualTo(1);
        assertThat(count("github_webhook_receipts")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT task_id FROM git_commit_task_links",UUID.class)).containsExactly(task);
        assertThat(jdbc.queryForObject("SELECT message FROM git_commits",String.class)).isEqualTo("legacy");
    }
}
