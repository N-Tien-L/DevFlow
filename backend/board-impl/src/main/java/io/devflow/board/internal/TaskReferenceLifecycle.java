package io.devflow.board.internal;

import io.devflow.common.event.TaskReferencesRemovedEvent;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Called while the Board deletion lock and transaction are held. */
@Component
class TaskReferenceLifecycle {
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    TaskReferenceLifecycle(JdbcTemplate jdbc, ApplicationEventPublisher events) { this.jdbc = jdbc; this.events = events; }
    void removeTask(UUID id) { events.publishEvent(new TaskReferencesRemovedEvent(java.util.Set.of(id))); }
    void removeBoard(UUID id) {
        // Keyset batches avoid loading an entire board's tasks into heap.
        UUID after = null;
        while (true) {
            var ids = after == null
                    ? jdbc.queryForList("SELECT t.id FROM tasks t JOIN columns c ON c.id=t.column_id "
                            + "WHERE c.board_id=? ORDER BY t.id LIMIT 100", UUID.class, id)
                    : jdbc.queryForList("SELECT t.id FROM tasks t JOIN columns c ON c.id=t.column_id "
                            + "WHERE c.board_id=? AND t.id>? ORDER BY t.id LIMIT 100", UUID.class, id, after);
            if (ids.isEmpty()) return;
            events.publishEvent(new TaskReferencesRemovedEvent(new HashSet<>(ids)));
            after = ids.get(ids.size() - 1);
        }
    }
}
