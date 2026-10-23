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
import java.util.ArrayList;
import java.util.List;

/** A status column belonging to one board. */
@Entity
@Table(name = "columns")
public class ColumnEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "board_id", nullable = false)
    private BoardEntity board;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "position", nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_category", nullable = false, length = 50)
    private StatusCategory statusCategory;

    @OneToMany(mappedBy = "column", cascade = {CascadeType.PERSIST, CascadeType.MERGE, CascadeType.REMOVE})
    private List<TaskEntity> tasks = new ArrayList<>();

    protected ColumnEntity() {
        // Required by JPA.
    }

    public ColumnEntity(BoardEntity board, String name, int position, StatusCategory statusCategory) {
        if (board == null) {
            throw new IllegalArgumentException("board must not be null");
        }
        this.name = name;
        this.position = position;
        this.statusCategory = statusCategory;
        board.addColumn(this);
    }

    public BoardEntity getBoard() {
        return board;
    }

    void attachToBoard(BoardEntity board) {
        if (board == null) {
            throw new IllegalArgumentException("board must not be null");
        }
        if (this.board != null && this.board != board) {
            this.board.removeColumn(this);
        }
        this.board = board;
        if (!board.getColumns().contains(this)) {
            board.getColumns().add(this);
        }
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public StatusCategory getStatusCategory() {
        return statusCategory;
    }

    public void setStatusCategory(StatusCategory statusCategory) {
        this.statusCategory = statusCategory;
    }

    public List<TaskEntity> getTasks() {
        return tasks;
    }

    public void addTask(TaskEntity task) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        task.moveTo(this);
    }

    /** Removes a child from this in-memory collection; it does not delete the database row. */
    public void removeTask(TaskEntity task) {
        tasks.removeIf(candidate -> sameEntity(candidate, task));
    }

    private static boolean sameEntity(BaseEntity left, BaseEntity right) {
        return left == right || (left.getId() != null && left.getId().equals(right.getId()));
    }
}
