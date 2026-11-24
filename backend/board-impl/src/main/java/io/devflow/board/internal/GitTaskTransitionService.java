package io.devflow.board.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.board.internal.entity.StatusCategory;
import io.devflow.board.internal.repository.BoardRepository;
import io.devflow.board.internal.repository.ColumnRepository;
import io.devflow.board.internal.repository.TaskRepository;
import io.devflow.common.event.TaskStatusChangedEvent;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Atomic Board consumer. Exceptions escape to GitCI's bounded retry queue. */
@Service
public class GitTaskTransitionService {
    private final BoardRepository boards;
    private final TaskRepository tasks;
    private final ColumnRepository columns;
    private final TaskMoveExecutor moves;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final boolean enabled;
    private final io.micrometer.core.instrument.MeterRegistry metrics;

    public GitTaskTransitionService(BoardRepository boards, TaskRepository tasks, ColumnRepository columns,
            TaskMoveExecutor moves, JdbcTemplate jdbc, ObjectMapper json,
            io.micrometer.core.instrument.MeterRegistry metrics,
            @Value("${devflow.board.git.auto-transition-enabled:false}") boolean enabled) {
        this.boards = boards; this.tasks = tasks; this.columns = columns;
        this.moves = moves; this.jdbc = jdbc; this.json = json; this.enabled = enabled; this.metrics = metrics;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 6)
    public String apply(GitTaskTransition event) {
        StatusCategory target = event.eventType() == null ? null : event.target();
        if (event.eventId() == null || event.repositoryId() == null || event.workspaceId() == null
                || event.taskId() == null || event.occurredAt() == null || target == null
                || event.objectKey() == null || event.objectKey().isBlank()
                || event.repo() == null || event.repo().isBlank()) {
            return "INVALID_EVENT";
        }
        if (!enabled) return "DISABLED";
        Instant receivedAt = Instant.now();
        jdbc.execute("SET LOCAL lock_timeout = '2s'");
        jdbc.execute("SET LOCAL statement_timeout = '4s'");
        var boardId = tasks.findBoardIdByTaskId(event.taskId()).orElse(null);
        if (boardId == null) return "MISSING";
        long lockStarted = System.nanoTime();
        io.devflow.board.internal.entity.BoardEntity board;
        try { board = boards.findByIdForUpdate(boardId).orElse(null); }
        finally {
            try { metrics.timer("devflow.board.git.lock.wait").record(System.nanoTime() - lockStarted,
                    java.util.concurrent.TimeUnit.NANOSECONDS); }
            catch (RuntimeException ignored) { /* Observability cannot change the transaction result. */ }
        }
        if (board == null) return "MISSING";
        var task = tasks.findTaskWithBoard(event.taskId()).orElse(null);
        if (task == null) return "MISSING";
        if (!task.getColumn().getBoard().getId().equals(boardId)
                || !board.getWorkspaceId().equals(event.workspaceId())) {
            return "TENANT_MISMATCH";
        }
        String fingerprint = fingerprint(event);
        var receipts = jdbc.queryForList("SELECT fingerprint FROM board_git_event_receipts WHERE event_id=?",
                String.class, event.eventId());
        if (!receipts.isEmpty()) {
            if (!receipts.get(0).equals(fingerprint)) throw new GitTransitionException("EVENT_CONFLICT");
            return "DUPLICATE";
        }
        String outcome;
        if (board.isArchived()) outcome = "ARCHIVED";
        else if (task.getLastManualStatusChangedAt() != null
                && !event.occurredAt().isAfter(task.getLastManualStatusChangedAt())) outcome = "STALE_MANUAL";
        else if (!GitTaskTransition.advances(task.getColumn().getStatusCategory(), target)) outcome = "NOOP";
        else {
            var destinations = columns.findByBoard_IdOrderByPositionAscIdAsc(boardId).stream()
                    .filter(column -> column.getStatusCategory() == target).toList();
            if (destinations.isEmpty()) outcome = "SKIPPED_TARGET_MISSING";
            else if (destinations.size() != 1) outcome = "SKIPPED_TARGET_AMBIGUOUS";
            else {
                try {
                    moves.move(task, board, destinations.get(0), null,
                            TaskStatusChangedEvent.StatusChangeCause.GIT, null, event.eventId());
                } catch (BoardApiException exception) {
                    if (!"TASK_LIMIT_REACHED".equals(exception.code())) throw exception;
                    throw new GitTransitionException("TARGET_FULL");
                }
                outcome = "APPLIED";
            }
        }
        jdbc.update("""
                INSERT INTO board_git_event_receipts(event_id,task_id,repository_id,workspace_id,
                    event_type,fingerprint,outcome,received_at) VALUES (?,?,?,?,?,?,?,?)
                """, event.eventId(), event.taskId(), event.repositoryId(), event.workspaceId(),
                event.eventType(), fingerprint, outcome, Timestamp.from(receivedAt));
        return outcome;
    }

    private String fingerprint(GitTaskTransition event) {
        try {
            byte[] bytes = json.writeValueAsBytes(Arrays.asList(event.eventId(), event.repositoryId(),
                    event.workspaceId(), event.taskId(), event.eventType(), event.occurredAt().toString(),
                    event.objectKey(), event.repo(), event.author(), event.actorId()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not fingerprint Git event", exception);
        }
    }
}
