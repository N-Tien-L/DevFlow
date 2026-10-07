package io.devflow.board.internal;

import io.devflow.board.api.ColumnStatusCategory;
import io.devflow.board.api.CreateTaskRequest;
import io.devflow.board.api.MoveTaskRequest;
import io.devflow.board.api.TaskListItem;
import io.devflow.board.api.TaskMoveResponse;
import io.devflow.board.api.TaskPageResponse;
import io.devflow.board.api.TaskResponse;
import io.devflow.board.api.UpdateTaskRequest;
import io.devflow.board.internal.entity.BoardEntity;
import io.devflow.board.internal.entity.ColumnEntity;
import io.devflow.board.internal.entity.TaskEntity;
import io.devflow.board.internal.repository.BoardRepository;
import io.devflow.board.internal.repository.ColumnRepository;
import io.devflow.board.internal.repository.TaskRepository;
import io.devflow.common.security.AuthenticatedActor;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Task lifecycle and ordering rules; all writes use the Board lock protocol from T-012. */
@Service
public class TaskManagementService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_TITLE_CODE_POINTS = 255;
    private static final int MAX_DESCRIPTION_CODE_POINTS = 10_000;

    private final TaskRepository taskRepository;
    private final ColumnRepository columnRepository;
    private final BoardRepository boardRepository;
    private final AuthenticatedActorProvider actorProvider;
    private final WorkspaceMembershipPermissionService permissionService;
    private final BoardAuditService auditService;
    private final int maxTasksPerColumn;

    public TaskManagementService(
            TaskRepository taskRepository,
            ColumnRepository columnRepository,
            BoardRepository boardRepository,
            AuthenticatedActorProvider actorProvider,
            WorkspaceMembershipPermissionService permissionService,
            BoardAuditService auditService,
            @Value("${devflow.board.tasks.max-per-column:1000}") int maxTasksPerColumn) {
        if (maxTasksPerColumn < 1) {
            throw new IllegalArgumentException("Maximum tasks per column must be positive");
        }
        this.taskRepository = taskRepository;
        this.columnRepository = columnRepository;
        this.boardRepository = boardRepository;
        this.actorProvider = actorProvider;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.maxTasksPerColumn = maxTasksPerColumn;
    }

    @Transactional
    public TaskResponse createTask(UUID columnId, CreateTaskRequest request) {
        UUID actorId = actorProvider.requireUserId();
        UUID boardId = columnRepository.findBoardIdByColumnId(columnId)
                .orElseThrow(BoardApiException::notFound);
        BoardEntity board = lockAccessibleBoard(boardId, actorId);
        requireActive(board);
        ColumnEntity column = columnRepository.findByIdAndBoard_Id(columnId, boardId)
                .orElseThrow(BoardApiException::notFound);
        if (request == null) {
            throw BoardApiException.badRequest("INVALID_REQUEST", "Task data is required.");
        }

        String title = cleanRequired(request.getTitle(), MAX_TITLE_CODE_POINTS, "title");
        String description = cleanDescription(request.getDescription());
        if (request.isPriorityProvided() && request.getPriority() == null) {
            throw BoardApiException.badRequest("PRIORITY_REQUIRED", "priority cannot be null.");
        }
        io.devflow.board.internal.entity.TaskPriority priority = request.getPriority() == null
                ? io.devflow.board.internal.entity.TaskPriority.MEDIUM
                : toEntityPriority(request.getPriority());
        UUID assigneeId = request.getAssigneeId();
        validateAssignee(assigneeId, board.getWorkspaceId());
        Instant dueDate = normalizeDueDate(request.getDueDate());

        List<TaskEntity> tasks = taskRepository.findByColumn_IdOrderByPositionAscIdAsc(columnId);
        requireCapacity(tasks.size());
        normalizePositions(tasks);

        TaskEntity task = new TaskEntity(column, title, tasks.size());
        task.setDescription(description);
        task.setPriority(priority);
        task.setAssigneeId(assigneeId);
        task.setDueDate(dueDate);
        task = taskRepository.saveAndFlush(task);
        List<String> changedFields = new ArrayList<>(List.of("title", "priority", "position", "columnId"));
        if (description != null) {
            changedFields.add("description");
        }
        if (assigneeId != null) {
            changedFields.add("assigneeId");
        }
        if (dueDate != null) {
            changedFields.add("dueDate");
        }
        auditService.record(actorId, "CREATE", "TASK", task.getId(), board.getWorkspaceId(), changedFields);
        return toResponse(task, board);
    }

    @Transactional(readOnly = true)
    public TaskPageResponse listTasks(UUID columnId, int page, int size) {
        UUID actorId = actorProvider.requireUserId();
        validatePage(page, size);
        UUID boardId = columnRepository.findBoardIdByColumnId(columnId)
                .orElseThrow(BoardApiException::notFound);
        BoardEntity board = boardRepository.findById(boardId).orElseThrow(BoardApiException::notFound);
        requireMember(actorId, board.getWorkspaceId());
        columnRepository.findByIdAndBoard_Id(columnId, boardId).orElseThrow(BoardApiException::notFound);

        Page<TaskEntity> results = taskRepository.findAllByColumn_Id(
                columnId,
                PageRequest.of(page, size, Sort.by(Sort.Order.asc("position"), Sort.Order.asc("id"))));
        return new TaskPageResponse(
                results.getContent().stream().map(TaskManagementService::toListItem).toList(),
                results.getNumber(), results.getSize(), results.getTotalElements(), results.getTotalPages());
    }

    @Transactional(readOnly = true)
    public TaskResponse getTask(UUID taskId) {
        UUID actorId = actorProvider.requireUserId();
        TaskEntity task = taskRepository.findTaskWithBoard(taskId).orElseThrow(BoardApiException::notFound);
        BoardEntity board = task.getColumn().getBoard();
        requireMember(actorId, board.getWorkspaceId());
        return toResponse(task, board);
    }

    @Transactional
    public TaskResponse replaceTask(UUID taskId, UpdateTaskRequest request) {
        UUID actorId = actorProvider.requireUserId();
        UUID boardId = taskRepository.findBoardIdByTaskId(taskId).orElseThrow(BoardApiException::notFound);
        BoardEntity board = lockAccessibleBoard(boardId, actorId);
        requireActive(board);
        TaskEntity task = taskRepository.findTaskWithBoard(taskId).orElseThrow(BoardApiException::notFound);
        if (!task.getColumn().getBoard().getId().equals(boardId)) {
            throw BoardApiException.notFound();
        }
        if (request == null || request.title() == null || request.priority() == null) {
            throw BoardApiException.badRequest("REQUIRED_FIELDS_MISSING", "title and priority are required.");
        }

        String title = cleanRequired(request.title(), MAX_TITLE_CODE_POINTS, "title");
        String description = cleanDescription(request.description());
        io.devflow.board.internal.entity.TaskPriority priority = toEntityPriority(request.priority());
        validateAssignee(request.assigneeId(), board.getWorkspaceId());
        Instant dueDate = normalizeDueDate(request.dueDate());
        List<String> changedFields = new ArrayList<>();
        if (!Objects.equals(task.getTitle(), title)) {
            task.setTitle(title);
            changedFields.add("title");
        }
        if (!Objects.equals(task.getDescription(), description)) {
            task.setDescription(description);
            changedFields.add("description");
        }
        if (task.getPriority() != priority) {
            task.setPriority(priority);
            changedFields.add("priority");
        }
        if (!Objects.equals(task.getAssigneeId(), request.assigneeId())) {
            task.setAssigneeId(request.assigneeId());
            changedFields.add("assigneeId");
        }
        if (!Objects.equals(task.getDueDate(), dueDate)) {
            task.setDueDate(dueDate);
            changedFields.add("dueDate");
        }
        if (!changedFields.isEmpty()) {
            taskRepository.flush();
            auditService.record(actorId, "UPDATE", "TASK", taskId, board.getWorkspaceId(), changedFields);
        }
        return toResponse(task, board);
    }

    @Transactional
    public void deleteTask(UUID taskId) {
        UUID actorId = actorProvider.requireUserId();
        UUID boardId = taskRepository.findBoardIdByTaskId(taskId).orElseThrow(BoardApiException::notFound);
        BoardEntity board = lockAccessibleBoard(boardId, actorId);
        requireActive(board);
        TaskEntity task = taskRepository.findTaskWithBoard(taskId).orElseThrow(BoardApiException::notFound);
        if (!task.getColumn().getBoard().getId().equals(boardId)) {
            throw BoardApiException.notFound();
        }
        UUID columnId = task.getColumn().getId();
        List<TaskEntity> tasks = taskRepository.findByColumn_IdOrderByPositionAscIdAsc(columnId);
        task.getColumn().removeTask(task);
        taskRepository.delete(task);
        tasks.removeIf(candidate -> candidate.getId().equals(taskId));
        normalizePositions(tasks);
        taskRepository.flush();
        auditService.record(actorId, "DELETE", "TASK", taskId, board.getWorkspaceId(),
                List.of("task", "comments", "tagMappings", "position"));
    }

    @Transactional
    public TaskMoveResponse moveTask(UUID taskId, MoveTaskRequest request) {
        UUID actorId = actorProvider.requireUserId();
        UUID boardId = taskRepository.findBoardIdByTaskId(taskId).orElseThrow(BoardApiException::notFound);
        BoardEntity board = lockAccessibleBoard(boardId, actorId);
        requireActive(board);
        TaskEntity task = taskRepository.findTaskWithBoard(taskId).orElseThrow(BoardApiException::notFound);
        if (!task.getColumn().getBoard().getId().equals(boardId)) {
            throw BoardApiException.notFound();
        }
        if (request == null || request.columnId() == null || request.position() == null) {
            throw BoardApiException.badRequest("INVALID_MOVE", "columnId and position are required.");
        }
        UUID sourceColumnId = task.getColumn().getId();
        ColumnEntity destination = columnRepository.findByIdAndBoard_Id(request.columnId(), boardId)
                .orElseThrow(BoardApiException::notFound);
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
        int targetPosition = request.position();
        if (targetPosition > destinationWithoutTask.size()) {
            throw BoardApiException.badRequest("INVALID_POSITION",
                    "position must be between 0 and " + destinationWithoutTask.size() + ".");
        }

        List<TaskEntity> reorderedDestination = new ArrayList<>(destinationWithoutTask);
        reorderedDestination.add(targetPosition, task);
        boolean sameColumn = sourceColumnId.equals(destinationColumnId);
        boolean changed = !sameColumn || !sameOrder(sourceTasks, reorderedDestination);

        if (!sameColumn) {
            task.moveTo(destination);
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
                        actorId, taskId, board.getWorkspaceId(), sourceColumnId, destinationColumnId);
            }
        }
        return new TaskMoveResponse(toResponse(task, board), sourceColumnId, destinationColumnId);
    }

    private BoardEntity lockAccessibleBoard(UUID boardId, UUID actorId) {
        UUID workspaceId = boardRepository.findWorkspaceIdByBoardId(boardId)
                .orElseThrow(BoardApiException::notFound);
        requireMember(actorId, workspaceId);
        BoardEntity board = boardRepository.findByIdForUpdate(boardId).orElseThrow(BoardApiException::notFound);
        requireMember(actorId, board.getWorkspaceId());
        return board;
    }

    private void requireMember(UUID actorId, UUID workspaceId) {
        if (!permissionService.isMember(actorId, workspaceId)) {
            throw BoardApiException.notFound();
        }
    }

    private static void requireActive(BoardEntity board) {
        if (board.isArchived()) {
            throw BoardApiException.boardArchived();
        }
    }

    private void validateAssignee(UUID assigneeId, UUID workspaceId) {
        if (assigneeId != null && !permissionService.isMember(assigneeId, workspaceId)) {
            throw BoardApiException.invalidAssignee();
        }
    }

    private void requireCapacity(int existingCount) {
        if (existingCount >= maxTasksPerColumn) {
            throw BoardApiException.conflict("TASK_LIMIT_REACHED", "The destination column is full.");
        }
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw BoardApiException.badRequest("INVALID_PAGE", "page must be non-negative and size must be 1..100.");
        }
    }

    private static String cleanRequired(String value, int maxCodePoints, String field) {
        if (value == null) {
            throw BoardApiException.badRequest("FIELD_REQUIRED", field + " is required.");
        }
        String cleaned = Jsoup.clean(value, Safelist.none()).strip();
        if (!StringUtils.hasText(cleaned)) {
            throw BoardApiException.badRequest("FIELD_REQUIRED", field + " must contain visible text.");
        }
        if (cleaned.codePointCount(0, cleaned.length()) > maxCodePoints) {
            throw BoardApiException.badRequest("FIELD_TOO_LONG", field + " exceeds the maximum length.");
        }
        return cleaned;
    }

    private static String cleanDescription(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = Jsoup.clean(value, Safelist.none());
        if (cleaned.codePointCount(0, cleaned.length()) > MAX_DESCRIPTION_CODE_POINTS) {
            throw BoardApiException.badRequest("DESCRIPTION_TOO_LONG", "description exceeds 10000 characters.");
        }
        return cleaned;
    }

    private static Instant normalizeDueDate(Instant dueDate) {
        if (dueDate == null) {
            return null;
        }
        try {
            int year = dueDate.atOffset(java.time.ZoneOffset.UTC).getYear();
            if (year < 1 || year > 9999) {
                throw BoardApiException.badRequest("INVALID_DUE_DATE", "dueDate must be between years 0001 and 9999.");
            }
            return dueDate.truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeException exception) {
            throw BoardApiException.badRequest("INVALID_DUE_DATE", "dueDate is outside the supported range.");
        }
    }

    private static io.devflow.board.internal.entity.TaskPriority toEntityPriority(
            io.devflow.board.api.TaskPriority priority) {
        return io.devflow.board.internal.entity.TaskPriority.valueOf(priority.name());
    }

    private static io.devflow.board.api.TaskPriority toApiPriority(
            io.devflow.board.internal.entity.TaskPriority priority) {
        return io.devflow.board.api.TaskPriority.valueOf(priority.name());
    }

    private static TaskResponse toResponse(TaskEntity task, BoardEntity board) {
        ColumnEntity column = task.getColumn();
        return new TaskResponse(
                task.getId(), column.getId(), board.getId(), board.getWorkspaceId(), task.getTitle(),
                task.getDescription(), toApiPriority(task.getPriority()), task.getAssigneeId(), task.getDueDate(),
                task.getPosition(), ColumnStatusCategory.valueOf(column.getStatusCategory().name()),
                task.getCreatedAt(), task.getUpdatedAt());
    }

    private static TaskListItem toListItem(TaskEntity task) {
        ColumnEntity column = task.getColumn();
        return new TaskListItem(
                task.getId(), column.getId(), task.getTitle(), toApiPriority(task.getPriority()),
                task.getAssigneeId(), task.getDueDate(), task.getPosition(),
                ColumnStatusCategory.valueOf(column.getStatusCategory().name()), task.getUpdatedAt());
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
