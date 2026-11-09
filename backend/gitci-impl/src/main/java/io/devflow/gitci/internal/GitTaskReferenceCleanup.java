package io.devflow.gitci.internal;

import io.devflow.common.event.TaskReferencesRemovedEvent;
import io.devflow.common.event.WorkspaceDeletedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class GitTaskReferenceCleanup {
    private final JdbcTemplate jdbc;
    GitTaskReferenceCleanup(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void remove(TaskReferencesRemovedEvent event) {
        var named = new NamedParameterJdbcTemplate(jdbc);
        var params = java.util.Map.of("ids", event.taskIds());
        named.update("DELETE FROM git_domain_event_outbox WHERE task_id IN (:ids)", params);
        named.update("UPDATE git_commits SET task_id=NULL WHERE task_id IN (:ids)", params);
        named.update("UPDATE pull_requests SET task_id=NULL WHERE task_id IN (:ids)", params);
        // Return the surviving parent IDs to restore compatibility mirrors after multi -> single cleanup.
        var commits = named.queryForList("DELETE FROM git_commit_task_links WHERE task_id IN (:ids) RETURNING commit_id", params, java.util.UUID.class);
        var prs = named.queryForList("DELETE FROM git_pull_request_task_links WHERE task_id IN (:ids) RETURNING pull_request_id", params, java.util.UUID.class);
        commits.stream().distinct().forEach(id -> jdbc.update("UPDATE git_commits SET task_id=(SELECT CASE WHEN count(*)=1 THEN (array_agg(task_id))[1] END FROM git_commit_task_links WHERE commit_id=?) WHERE id=?", id, id));
        prs.stream().distinct().forEach(id -> jdbc.update("UPDATE pull_requests SET task_id=(SELECT CASE WHEN count(*)=1 THEN (array_agg(task_id))[1] END FROM git_pull_request_task_links WHERE pull_request_id=?) WHERE id=?", id, id));
    }
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void removeWorkspace(WorkspaceDeletedEvent event) {
        jdbc.update("DELETE FROM repositories WHERE workspace_id=?", event.workspaceId());
    }
}
