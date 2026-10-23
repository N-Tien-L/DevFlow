package io.devflow.auth.internal.repository;

import io.devflow.auth.internal.entity.UserEntity;
import io.devflow.auth.internal.entity.UserStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link UserEntity}.
 */
@Repository
public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Removes abandoned sign-ups (accounts never verified before the cutoff).
     * Their verification tokens are removed by the {@code ON DELETE CASCADE} foreign key.
     */
    @Modifying
    @Query("DELETE FROM UserEntity u WHERE u.status = :status AND u.createdAt < :cutoff")
    int deleteByStatusAndCreatedAtBefore(@Param("status") UserStatus status, @Param("cutoff") Instant cutoff);
}
