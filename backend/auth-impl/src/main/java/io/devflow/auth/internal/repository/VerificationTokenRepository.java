package io.devflow.auth.internal.repository;

import io.devflow.auth.internal.entity.VerificationTokenEntity;
import io.devflow.auth.internal.entity.VerificationTokenType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link VerificationTokenEntity}.
 */
@Repository
public interface VerificationTokenRepository extends JpaRepository<VerificationTokenEntity, UUID> {

    @Query("SELECT t FROM VerificationTokenEntity t JOIN FETCH t.user WHERE t.tokenHash = :tokenHash")
    Optional<VerificationTokenEntity> findByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * Atomically consumes a token. Returns 0 when another request consumed it first,
     * which makes concurrent clicks on the same link safe.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE VerificationTokenEntity t SET t.usedAt = :now, t.updatedAt = :now "
            + "WHERE t.id = :id AND t.usedAt IS NULL")
    int markUsed(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Invalidates every still-usable token of the given type for a user (e.g. before issuing a new one).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE VerificationTokenEntity t SET t.usedAt = :now, t.updatedAt = :now "
            + "WHERE t.user.id = :userId AND t.type = :type AND t.usedAt IS NULL")
    int invalidateActiveTokens(@Param("userId") UUID userId,
                               @Param("type") VerificationTokenType type,
                               @Param("now") Instant now);

    /**
     * Purges tokens that were consumed or expired before the cutoff.
     */
    @Modifying
    @Query("DELETE FROM VerificationTokenEntity t WHERE t.expiresAt < :cutoff OR t.usedAt < :cutoff")
    int deleteStaleTokens(@Param("cutoff") Instant cutoff);
}
