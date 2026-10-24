package io.devflow.auth.internal.repository;

import io.devflow.auth.internal.entity.UserEntity;
import io.devflow.auth.internal.entity.UserStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
     * The user's current token version; empty when the user does not exist.
     */
    @Query("SELECT u.tokenVersion FROM UserEntity u WHERE u.id = :id")
    Optional<Integer> findTokenVersion(@Param("id") UUID id);

    /**
     * Loads a user with a row lock, serialising that user's concurrent workspace creations so the
     * per-user workspace quota cannot be raced.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserEntity u WHERE u.id = :id")
    Optional<UserEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Removes abandoned sign-ups (accounts never verified before the cutoff).
     * Their verification tokens are removed by the {@code ON DELETE CASCADE} foreign key.
     */
    @Modifying
    @Query("DELETE FROM UserEntity u WHERE u.status = :status AND u.createdAt < :cutoff")
    int deleteByStatusAndCreatedAtBefore(@Param("status") UserStatus status, @Param("cutoff") Instant cutoff);
}
