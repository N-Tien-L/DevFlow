package io.devflow.board.internal.repository;

import io.devflow.board.internal.entity.BoardAuditEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BoardAuditRepository extends JpaRepository<BoardAuditEntity, UUID> {
}
