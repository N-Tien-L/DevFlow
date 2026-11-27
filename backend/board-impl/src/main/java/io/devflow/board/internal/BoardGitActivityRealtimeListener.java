package io.devflow.board.internal;

import io.devflow.board.api.BoardMutationData;
import io.devflow.board.api.BoardMutationMessage;
import io.devflow.board.api.BoardMutationType;
import io.devflow.board.api.BoardRefreshTarget;
import io.devflow.common.event.TaskGitActivityChangedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Translates GitCI after-commit task activity signals into board-scoped STOMP invalidations
 * ({@code CARD_UPDATED} with {@code changedFields=["gitActivity"]}) without acquiring Board write locks
 * or importing GitCI internals.
 */
@Component
class BoardGitActivityRealtimeListener {

    private static final Logger log = LoggerFactory.getLogger(BoardGitActivityRealtimeListener.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final BoardRealtimeBroadcaster broadcaster;
    private final MeterRegistry metrics;

    BoardGitActivityRealtimeListener(
            JdbcTemplate jdbc,
            BoardRealtimeBroadcaster broadcaster,
            MeterRegistry metrics) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.broadcaster = broadcaster;
        this.metrics = metrics;
    }

    @EventListener
    public void on(TaskGitActivityChangedEvent event) {
        if (event == null || event.workspaceId() == null || event.taskIds() == null || event.taskIds().isEmpty()) {
            return;
        }
        String kind = event.kind() == null ? "UNKNOWN" : event.kind().name();
        broadcastForTasks(event.workspaceId(), event.taskIds(), kind, event.eventId());
    }

    /** Broadcasts a non-structural {@code CARD_UPDATED(gitActivity)} invalidation scoped by workspace. */
    void broadcastForTasks(UUID workspaceId, Set<UUID> taskIds, String kind, UUID sourceEventId) {
        if (workspaceId == null || taskIds == null || taskIds.isEmpty()) {
            return;
        }
        try {
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("workspaceId", workspaceId)
                    .addValue("taskIds", taskIds);

            Map<UUID, List<TaskLocation>> byBoard = new LinkedHashMap<>();
            jdbc.query("""
                    SELECT t.id AS task_id, t.column_id, c.board_id
                    FROM tasks t
                    JOIN columns c ON c.id = t.column_id
                    JOIN boards b ON b.id = c.board_id
                    WHERE b.workspace_id = :workspaceId AND t.id IN (:taskIds)
                    """, params, rs -> {
                UUID boardId = rs.getObject("board_id", UUID.class);
                UUID taskId = rs.getObject("task_id", UUID.class);
                UUID columnId = rs.getObject("column_id", UUID.class);
                byBoard.computeIfAbsent(boardId, ignored -> new ArrayList<>())
                        .add(new TaskLocation(taskId, columnId));
            });

            if (byBoard.isEmpty()) {
                recordMetric(kind, "skipped");
                return;
            }

            UUID correlationId = BoardRequestContext.correlationId();
            for (Map.Entry<UUID, List<TaskLocation>> entry : byBoard.entrySet()) {
                UUID boardId = entry.getKey();
                List<TaskLocation> locations = entry.getValue();
                Set<UUID> columnIds = new LinkedHashSet<>();
                for (TaskLocation location : locations) {
                    columnIds.add(location.columnId());
                }
                UUID singleTaskId = locations.size() == 1 ? locations.get(0).taskId() : null;
                UUID singleColumnId = locations.size() == 1 ? locations.get(0).columnId() : null;

                BoardMutationData data = new BoardMutationData(
                        singleTaskId,
                        singleColumnId,
                        null,
                        null,
                        null,
                        null,
                        List.copyOf(columnIds),
                        List.of("gitActivity"),
                        List.of(BoardRefreshTarget.TASK_LISTS, BoardRefreshTarget.TASK_DETAIL));

                BoardMutationMessage message = new BoardMutationMessage(
                        BoardMutationMessage.CURRENT_SCHEMA_VERSION,
                        UUID.randomUUID(),
                        BoardMutationType.CARD_UPDATED,
                        boardId,
                        Instant.now(),
                        correlationId,
                        null,
                        data);

                broadcaster.broadcast(message);
                recordMetric(kind, "broadcast");
            }
        } catch (RuntimeException ex) {
            recordMetric(kind, "error");
            log.warn("Board Git activity STOMP broadcast failed kind={} eventId={} exceptionType={}",
                    kind, sourceEventId, ex.getClass().getSimpleName());
        }
    }

    private void recordMetric(String kind, String outcome) {
        try {
            metrics.counter("devflow.gitci.activity.events", "kind", kind, "outcome", outcome).increment();
        } catch (RuntimeException ignored) {
            // Observability failure must never interfere with best-effort notification.
        }
    }

    private record TaskLocation(UUID taskId, UUID columnId) {
    }
}
