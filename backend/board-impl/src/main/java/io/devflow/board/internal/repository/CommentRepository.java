package io.devflow.board.internal.repository;

import io.devflow.board.internal.entity.CommentEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence queries for comments in chronological order within their task. */
@Repository
public interface CommentRepository extends JpaRepository<CommentEntity, UUID> {

    List<CommentEntity> findByTask_IdOrderByCreatedAtAscIdAsc(UUID taskId);

    Optional<CommentEntity> findByIdAndTask_Id(UUID id, UUID taskId);
}
