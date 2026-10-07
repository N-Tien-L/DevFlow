package io.devflow.board.internal.entity;

import io.devflow.common.entity.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A task in a Kanban column. */
@Entity
@Table(name = "tasks")
public class TaskEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "column_id", nullable = false)
    private ColumnEntity column;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "position", nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 50)
    private TaskPriority priority = TaskPriority.MEDIUM;

    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Column(name = "due_date")
    private Instant dueDate;

    @OneToMany(mappedBy = "task", cascade = {CascadeType.PERSIST, CascadeType.MERGE, CascadeType.REMOVE})
    private List<CommentEntity> comments = new ArrayList<>();

    protected TaskEntity() {
        // Required by JPA.
    }

    public TaskEntity(ColumnEntity column, String title, int position) {
        if (column == null) {
            throw new IllegalArgumentException("column must not be null");
        }
        this.title = title;
        this.position = position;
        moveTo(column);
    }

    public ColumnEntity getColumn() {
        return column;
    }

    /** Changes the owning column while keeping both in-memory sides synchronized. */
    public void moveTo(ColumnEntity newColumn) {
        if (newColumn == null) {
            throw new IllegalArgumentException("column must not be null");
        }
        if (this.column != null && this.column != newColumn) {
            this.column.removeTask(this);
        }
        this.column = newColumn;
        if (!newColumn.getTasks().contains(this)) {
            newColumn.getTasks().add(this);
        }
    }

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

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public TaskPriority getPriority() {
        return priority;
    }

    public void setPriority(TaskPriority priority) {
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

    public List<CommentEntity> getComments() {
        return comments;
    }

    public void addComment(CommentEntity comment) {
        if (comment == null) {
            throw new IllegalArgumentException("comment must not be null");
        }
        comment.attachToTask(this);
    }

    /** Removes a child from this in-memory collection; it does not delete the database row. */
    public void removeComment(CommentEntity comment) {
        comments.removeIf(candidate -> sameEntity(candidate, comment));
    }

    private static boolean sameEntity(BaseEntity left, BaseEntity right) {
        return left == right || (left.getId() != null && left.getId().equals(right.getId()));
    }
}
