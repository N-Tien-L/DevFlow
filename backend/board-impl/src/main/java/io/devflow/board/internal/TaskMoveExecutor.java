package io.devflow.board.internal;

import io.devflow.board.api.*;
import io.devflow.board.internal.entity.*;
import io.devflow.board.internal.repository.TaskRepository;
import io.devflow.common.event.TaskStatusChangedEvent;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Shared mutation primitive. Callers must hold the Board write lock in an active transaction. */
@Component
public class TaskMoveExecutor {
    private final TaskRepository taskRepository;
    private final BoardAuditService auditService;
    private final TaskEventPublisher taskEventPublisher;
    private final BoardRealtimePublisher realtimePublisher;
    private final int maxTasksPerColumn;

    public TaskMoveExecutor(TaskRepository taskRepository, BoardAuditService auditService,
            TaskEventPublisher taskEventPublisher, BoardRealtimePublisher realtimePublisher,
            @Value("${devflow.board.tasks.max-per-column:1000}") int maxTasksPerColumn) {
        if (maxTasksPerColumn < 1) throw new IllegalArgumentException("Maximum tasks per column must be positive");
        this.taskRepository = taskRepository;
        this.auditService = auditService;
        this.taskEventPublisher = taskEventPublisher;
        this.realtimePublisher = realtimePublisher;
        this.maxTasksPerColumn = maxTasksPerColumn;
    }

    public void move(TaskEntity task, BoardEntity board, ColumnEntity destination, Integer position,
            TaskStatusChangedEvent.StatusChangeCause cause, UUID actorId, UUID sourceEventId) {
        UUID taskId = task.getId();
        if (!task.getColumn().getBoard().getId().equals(board.getId())
                || !destination.getBoard().getId().equals(board.getId())) {
            throw new IllegalArgumentException("A task move must remain on its locked board");
        }
        UUID sourceColumnId = task.getColumn().getId();
        int fromPosition = task.getPosition();
        ColumnEntity sourceColumn = task.getColumn();
        UUID destinationColumnId = destination.getId();
        List<TaskEntity> sourceTasks = taskRepository.findByColumn_IdOrderByPositionAscIdAsc(sourceColumnId);
        List<TaskEntity> sourceRemaining = sourceTasks.stream()
                .filter(candidate -> !candidate.getId().equals(taskId))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        List<TaskEntity> destinationTasks = sourceColumnId.equals(destinationColumnId)
                ? new ArrayList<>(sourceTasks)
                : taskRepository.findByColumn_IdOrderByPositionAscIdAsc(destinationColumnId);
        List<TaskEntity> destinationWithoutTask = destinationTasks.stream()
                .filter(candidate -> !candidate.getId().equals(taskId))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        requireCapacity(destinationWithoutTask.size());
        int targetPosition = position == null ? destinationWithoutTask.size() : position;
        if (targetPosition < 0 || targetPosition > destinationWithoutTask.size()) {
            throw BoardApiException.badRequest("INVALID_POSITION",
                    "position must be between 0 and " + destinationWithoutTask.size() + ".");
        }

        List<TaskEntity> reorderedDestination = new ArrayList<>(destinationWithoutTask);
        reorderedDestination.add(targetPosition, task);
        boolean sameColumn = sourceColumnId.equals(destinationColumnId);
        boolean changed = !sameColumn || !sameOrder(sourceTasks, reorderedDestination);

        if (!sameColumn) {
            task.moveTo(destination);
            if (cause == TaskStatusChangedEvent.StatusChangeCause.MANUAL) {
                task.setLastManualStatusChangedAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
            }
            normalizePositions(sourceRemaining);
        }
        normalizePositions(reorderedDestination);
        if (changed) {
            taskRepository.flush();
            if (sameColumn) {
                auditService.record(actorId, "REORDER", "TASK", taskId, board.getWorkspaceId(),
                        List.of("position"));
            } else {
                auditService.recordTaskMove(
                        actorId, taskId, board.getWorkspaceId(), sourceColumnId, destinationColumnId, sourceEventId);
            }
        }
        if (!sameColumn) {
            taskEventPublisher.publishStatusChanged(
                    taskId,
                    board.getId(),
                    board.getWorkspaceId(),
                    sourceColumnId,
                    destinationColumnId,
                    sourceColumn.getName(),
                    destination.getName(),
                    sourceColumn.getStatusCategory().name(),
                    destination.getStatusCategory().name(),
                    cause,
                    actorId);
        }
        if (changed) {
            BoardMutationType type = sameColumn ? BoardMutationType.CARD_REORDERED : BoardMutationType.CARD_MOVED;
            List<UUID> affectedColumns = sameColumn
                    ? List.of(sourceColumnId)
                    : List.of(sourceColumnId, destinationColumnId);
            List<String> changedFields = sameColumn
                    ? List.of("position")
                    : List.of("columnId", "position");
            realtimePublisher.publish(board.getId(), type,
                    new BoardMutationData(taskId,
                            sameColumn ? sourceColumnId : null,
                            sourceColumnId,
                            destinationColumnId,
                            fromPosition,
                            task.getPosition(),
                            affectedColumns,
                            changedFields,
                            List.of(BoardRefreshTarget.TASK_LISTS, BoardRefreshTarget.TASK_DETAIL)), actorId);
        }

    }

    private void requireCapacity(int existingCount) {
        if (existingCount >= maxTasksPerColumn) {
            throw BoardApiException.conflict("TASK_LIMIT_REACHED", "The destination column is full.");
        }
    }
    private static void normalizePositions(List<TaskEntity> tasks) {
        for (int index = 0; index < tasks.size(); index++) {
            tasks.get(index).setPosition(index);
        }
    }

    private static boolean sameOrder(List<TaskEntity> existing, List<TaskEntity> requested) {
        if (existing.size() != requested.size()) {
            return false;
        }
        for (int index = 0; index < existing.size(); index++) {
            if (!existing.get(index).getId().equals(requested.get(index).getId())
                    || existing.get(index).getPosition() != index) {
                return false;
            }
        }
        return true;
    }
}
