package io.devflow.board.internal.entity;

import io.devflow.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/** A comment attached to one task; author IDs are soft references owned by Auth. */
@Entity
@Table(name = "task_comments")
public class CommentEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private TaskEntity task;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    protected CommentEntity() {
        // Required by JPA.
    }

    public CommentEntity(TaskEntity task, UUID authorId, String content) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        this.authorId = authorId;
        this.content = content;
        attachToTask(task);
    }

    public TaskEntity getTask() {
        return task;
    }

    void attachToTask(TaskEntity task) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        if (this.task != null && this.task != task) {
            this.task.removeComment(this);
        }
        this.task = task;
        if (!task.getComments().contains(this)) {
            task.getComments().add(this);
        }
    }

    public UUID getAuthorId() {
        return authorId;
    }

    public void setAuthorId(UUID authorId) {
        this.authorId = authorId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
