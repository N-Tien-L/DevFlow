package io.devflow.board.internal.repository;

import io.devflow.board.internal.entity.TaskEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistence queries for tasks ordered within their column or board. */
@Repository
public interface TaskRepository extends JpaRepository<TaskEntity, UUID> {

    List<TaskEntity> findByColumn_IdOrderByPositionAscIdAsc(UUID columnId);

    @Query("""
            select task from TaskEntity task
            join task.column column
            where column.board.id = :boardId
            order by column.position asc, column.id asc, task.position asc, task.id asc
            """)
    List<TaskEntity> findByBoardIdInDisplayOrder(@Param("boardId") UUID boardId);

    Optional<TaskEntity> findByIdAndColumn_Board_Id(UUID id, UUID boardId);
}
