package io.devflow.board.internal;

import io.devflow.board.api.BoardApi;
import io.devflow.board.api.TaskSummary;
import io.devflow.board.internal.entity.TaskEntity;
import io.devflow.board.internal.repository.TaskRepository;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only public BoardApi implementation. Project ids represent workspace ids. */
@Service
@Transactional(readOnly = true)
public class BoardService implements BoardApi {

    private final TaskRepository taskRepository;

    public BoardService(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @Override
    public List<TaskSummary> findTasksByProject(UUID projectId) {
        return taskRepository.findByWorkspaceIdInDisplayOrder(projectId).stream()
                .map(this::toSummary)
                .toList();
    }

    @Override
    public Optional<TaskSummary> findTask(UUID taskId) {
        return taskRepository.findTaskWithBoard(taskId).map(this::toSummary);
    }

    private TaskSummary toSummary(TaskEntity task) {
        UUID workspaceId = task.getColumn().getBoard().getWorkspaceId();
        String status = task.getColumn().getStatusCategory().name();
        return new TaskSummary(
                task.getId(),
                workspaceId,
                task.getTitle(),
                status,
                task.getAssigneeId(),
                task.getDueDate() == null ? null : task.getDueDate().atZone(ZoneOffset.UTC).toLocalDate());
    }
}
