package io.devflow.board.internal;

import io.devflow.board.api.CreateTaskRequest;
import io.devflow.board.api.MoveTaskRequest;
import io.devflow.board.api.TaskMoveResponse;
import io.devflow.board.api.TaskPageResponse;
import io.devflow.board.api.TaskResponse;
import io.devflow.board.api.UpdateTaskRequest;
import io.devflow.board.api.CreateTaskCommentRequest;
import io.devflow.board.api.TaskCommentPageResponse;
import io.devflow.board.api.TaskCommentResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated task CRUD and within-board ordering endpoints. */
@RestController
@RequestMapping("/api/v1")
public class TaskController {

    private static final Pattern TASK_ETAG = Pattern.compile("^\\\"task-([0-9a-fA-F-]{36})-v(\\d+)\\\"$");

    private final TaskManagementService taskService;
    private final TaskMutationRequestGuard mutationGuard;
    private final TaskCommentService commentService;

    public TaskController(TaskManagementService taskService, TaskMutationRequestGuard mutationGuard,
            TaskCommentService commentService) {
        this.taskService = taskService;
        this.mutationGuard = mutationGuard;
        this.commentService = commentService;
    }

    @PostMapping("/columns/{columnId}/tasks")
    public ResponseEntity<TaskResponse> createTask(
            @PathVariable("columnId") UUID columnId,
            @Valid @RequestBody CreateTaskRequest request) {
        mutationGuard.authorizeMutation();
        TaskResponse task = taskService.createTask(columnId, request);
        return ResponseEntity.created(URI.create("/api/v1/tasks/" + task.id())).body(task);
    }

    @GetMapping("/columns/{columnId}/tasks")
    public TaskPageResponse listTasks(
            @PathVariable("columnId") UUID columnId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return taskService.listTasks(columnId, page, size);
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<TaskResponse> getTask(@PathVariable("taskId") UUID taskId) {
        TaskResponse task = taskService.getTask(taskId);
        return ResponseEntity.ok().eTag(taskEtag(task)).body(task);
    }

    @PutMapping("/tasks/{taskId}")
    public ResponseEntity<TaskResponse> replaceTask(
            @PathVariable("taskId") UUID taskId,
            @Valid @RequestBody UpdateTaskRequest request,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        long expectedVersion = parseVersion(taskId, ifMatch);
        mutationGuard.authorizeMutation();
        TaskResponse task = taskService.replaceTask(taskId, request, expectedVersion);
        return ResponseEntity.ok().eTag(taskEtag(task)).body(task);
    }

    @GetMapping("/tasks/{taskId}/comments")
    public TaskCommentPageResponse listComments(@PathVariable("taskId") UUID taskId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return commentService.list(taskId, page, size);
    }

    @PostMapping("/tasks/{taskId}/comments")
    public ResponseEntity<TaskCommentResponse> createComment(@PathVariable("taskId") UUID taskId,
            @Valid @RequestBody CreateTaskCommentRequest request) {
        mutationGuard.authorizeMutation();
        TaskCommentResponse comment = commentService.create(taskId, request);
        return ResponseEntity.created(URI.create("/api/v1/tasks/" + taskId + "/comments/" + comment.id()))
                .body(comment);
    }

    @DeleteMapping("/tasks/{taskId}")
    public ResponseEntity<Void> deleteTask(
            @PathVariable("taskId") UUID taskId) {
        mutationGuard.authorizeMutation();
        taskService.deleteTask(taskId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/tasks/{taskId}/move")
    public TaskMoveResponse moveTask(
            @PathVariable("taskId") UUID taskId,
            @Valid @RequestBody MoveTaskRequest request) {
        mutationGuard.authorizeMutation();
        return taskService.moveTask(taskId, request);
    }

    private static long parseVersion(UUID taskId, String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) throw BoardApiException.preconditionRequired();
        Matcher matcher = TASK_ETAG.matcher(ifMatch);
        if (!matcher.matches() || !taskId.toString().equalsIgnoreCase(matcher.group(1)))
            throw BoardApiException.preconditionFailed();
        try {
            return Long.parseLong(matcher.group(2));
        } catch (NumberFormatException exception) {
            throw BoardApiException.preconditionFailed();
        }
    }

    private static String taskEtag(TaskResponse task) {
        return "task-" + task.id() + "-v" + task.version();
    }
}
