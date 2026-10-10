package io.devflow.board.internal;

import io.devflow.board.api.CreateTaskRequest;
import io.devflow.board.api.MoveTaskRequest;
import io.devflow.board.api.TaskMoveResponse;
import io.devflow.board.api.TaskPageResponse;
import io.devflow.board.api.TaskResponse;
import io.devflow.board.api.UpdateTaskRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
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

    private final TaskManagementService taskService;
    private final TaskMutationRequestGuard mutationGuard;

    public TaskController(TaskManagementService taskService, TaskMutationRequestGuard mutationGuard) {
        this.taskService = taskService;
        this.mutationGuard = mutationGuard;
    }

    @PostMapping("/columns/{columnId}/tasks")
    public ResponseEntity<TaskResponse> createTask(
            @PathVariable("columnId") UUID columnId,
            @Valid @RequestBody CreateTaskRequest request,
            @RequestHeader(name = "X-Turnstile-Token", required = false) String turnstileToken,
            HttpServletRequest servletRequest) {
        mutationGuard.authorizeMutation(turnstileToken, servletRequest.getRemoteAddr());
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
    public TaskResponse getTask(@PathVariable("taskId") UUID taskId) {
        return taskService.getTask(taskId);
    }

    @PutMapping("/tasks/{taskId}")
    public TaskResponse replaceTask(
            @PathVariable("taskId") UUID taskId,
            @Valid @RequestBody UpdateTaskRequest request,
            @RequestHeader(name = "X-Turnstile-Token", required = false) String turnstileToken,
            HttpServletRequest servletRequest) {
        mutationGuard.authorizeMutation(turnstileToken, servletRequest.getRemoteAddr());
        return taskService.replaceTask(taskId, request);
    }

    @DeleteMapping("/tasks/{taskId}")
    public ResponseEntity<Void> deleteTask(
            @PathVariable("taskId") UUID taskId,
            @RequestHeader(name = "X-Turnstile-Token", required = false) String turnstileToken,
            HttpServletRequest servletRequest) {
        mutationGuard.authorizeMutation(turnstileToken, servletRequest.getRemoteAddr());
        taskService.deleteTask(taskId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/tasks/{taskId}/move")
    public TaskMoveResponse moveTask(
            @PathVariable("taskId") UUID taskId,
            @Valid @RequestBody MoveTaskRequest request,
            @RequestHeader(name = "X-Turnstile-Token", required = false) String turnstileToken,
            HttpServletRequest servletRequest) {
        mutationGuard.authorizeMutation(turnstileToken, servletRequest.getRemoteAddr());
        return taskService.moveTask(taskId, request);
    }
}
