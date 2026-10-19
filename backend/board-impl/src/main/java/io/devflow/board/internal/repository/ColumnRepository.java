package io.devflow.board.internal.repository;

import io.devflow.board.internal.entity.ColumnEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence queries for board columns in deterministic display order. */
@Repository
public interface ColumnRepository extends JpaRepository<ColumnEntity, UUID> {

    List<ColumnEntity> findByBoard_IdOrderByPositionAscIdAsc(UUID boardId);

    List<ColumnEntity> findAllByBoard_IdOrderByPositionAscIdAsc(UUID boardId);

    Optional<ColumnEntity> findByIdAndBoard_Id(UUID id, UUID boardId);

    long countByBoard_Id(UUID boardId);

}
