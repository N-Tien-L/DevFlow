package io.devflow.board.internal.entity;

import io.devflow.common.entity.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Board-owned Kanban data. Workspace IDs are soft references owned by Auth. */
@Entity
@Table(name = "boards")
public class BoardEntity extends BaseEntity {

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "is_archived", nullable = false)
    private boolean archived;

    @OneToMany(mappedBy = "board", cascade = {CascadeType.PERSIST, CascadeType.MERGE, CascadeType.REMOVE})
    private List<ColumnEntity> columns = new ArrayList<>();

    protected BoardEntity() {
        // Required by JPA.
    }

    public BoardEntity(UUID workspaceId, String name) {
        this.workspaceId = workspaceId;
        this.name = name;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(UUID workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public List<ColumnEntity> getColumns() {
        return columns;
    }

    public void addColumn(ColumnEntity column) {
        if (column == null) {
            throw new IllegalArgumentException("column must not be null");
        }
        if (column.getBoard() != this) {
            column.attachToBoard(this);
        }
        if (!columns.contains(column)) {
            columns.add(column);
        }
    }

    /** Removes a child from this in-memory collection; it does not delete the database row. */
    public void removeColumn(ColumnEntity column) {
        columns.removeIf(candidate -> sameEntity(candidate, column));
    }

    private static boolean sameEntity(BaseEntity left, BaseEntity right) {
        return left == right || (left.getId() != null && left.getId().equals(right.getId()));
    }
}
