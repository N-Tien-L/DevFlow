package io.devflow.board.internal;

import io.devflow.common.event.TaskReferencesResolveRequestedEvent;
import io.devflow.common.event.TaskReferencesResolveRequestedEvent.Outcome;
import io.devflow.common.event.TaskReferencesResolveRequestedEvent.Resolution;
import java.util.HashMap;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class TaskReferenceResolver {
    private final NamedParameterJdbcTemplate jdbc;
    TaskReferenceResolver(org.springframework.jdbc.core.JdbcTemplate jdbc) { this.jdbc = new NamedParameterJdbcTemplate(jdbc); }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void resolve(TaskReferencesResolveRequestedEvent event) {
        var params = new MapSqlParameterSource("workspace", event.workspaceId())
                .addValue("numbers", event.keys().stream().map(k -> Integer.parseInt(k.substring(2))).toList());
        // Match Board's board-before-task lock protocol. Locks live through GitCI's commit.
        var boards = jdbc.queryForList("SELECT DISTINCT c.board_id FROM tasks t JOIN columns c ON c.id=t.column_id "
                + "WHERE t.key_workspace_id=:workspace AND t.task_number IN (:numbers)", params, UUID.class);
        var result = new HashMap<String, Resolution>();
        event.keys().forEach(k -> result.put(k, new Resolution(event.workspaceId(), k, null, Outcome.NOT_FOUND)));
        if (boards.isEmpty()) { event.respond(result); return; }
        jdbc.queryForList("SELECT id FROM boards WHERE id IN (:boards) ORDER BY id FOR SHARE",
                new MapSqlParameterSource("boards", boards), UUID.class);
        params.addValue("boards", boards);
        jdbc.query("SELECT t.id,t.task_number,b.workspace_id,b.is_archived FROM tasks t "
                + "JOIN columns c ON c.id=t.column_id JOIN boards b ON b.id=c.board_id "
                + "WHERE t.key_workspace_id=:workspace AND t.task_number IN (:numbers) AND b.id IN (:boards) ORDER BY t.id FOR SHARE OF t", params, rs -> {
                    UUID workspace = rs.getObject("workspace_id", UUID.class);
                    if (!workspace.equals(event.workspaceId())) throw new IllegalStateException("Task scope mismatch");
                    String key = "T-" + String.format(java.util.Locale.ROOT, "%03d", rs.getInt("task_number"));
                    boolean inactive = rs.getBoolean("is_archived");
                    result.put(key, new Resolution(workspace, key, inactive ? null : rs.getObject("id", UUID.class),
                            inactive ? Outcome.INACTIVE : Outcome.ELIGIBLE));
                });
        event.respond(result);
    }
}
