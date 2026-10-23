package io.devflow.board.api;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;

/** Request data for creating a task; IDs for actor, board, and position are server-owned. */
public final class CreateTaskRequest {

    @NotBlank
    private String title;

    private String description;

    private TaskPriority priority;
    private UUID assigneeId;
    private Instant dueDate;
    private boolean priorityProvided;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public TaskPriority getPriority() {
        return priority;
    }

    public void setPriority(TaskPriority priority) {
        this.priorityProvided = true;
        this.priority = priority;
    }

    public UUID getAssigneeId() {
        return assigneeId;
    }

    public void setAssigneeId(UUID assigneeId) {
        this.assigneeId = assigneeId;
    }

    public Instant getDueDate() {
        return dueDate;
    }

    public void setDueDate(Instant dueDate) {
        this.dueDate = dueDate;
    }

    public boolean isPriorityProvided() {
        return priorityProvided;
    }
}
