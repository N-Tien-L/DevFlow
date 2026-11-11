package io.devflow.ai.internal.repository;

import io.devflow.ai.internal.entity.CiSummaryEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link CiSummaryEntity}.
 */
@Repository
public interface CiSummaryRepository extends JpaRepository<CiSummaryEntity, UUID> {

    boolean existsByPipelineId(String pipelineId);

    Optional<CiSummaryEntity> findByPipelineId(String pipelineId);
}
