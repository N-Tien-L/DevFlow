package io.devflow.auth.internal.repository;

import io.devflow.auth.internal.entity.PersonalAccessTokenEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link PersonalAccessTokenEntity}.
 */
@Repository
public interface PersonalAccessTokenRepository extends JpaRepository<PersonalAccessTokenEntity, UUID> {

    @Query("SELECT t FROM PersonalAccessTokenEntity t JOIN FETCH t.user WHERE t.tokenPrefix = :tokenPrefix")
    Optional<PersonalAccessTokenEntity> findByTokenPrefix(@Param("tokenPrefix") String tokenPrefix);

    boolean existsByTokenPrefix(String tokenPrefix);

    /**
     * Unrevoked tokens of a user (expired ones included, so the owner can see and clean them up), newest first.
     */
    List<PersonalAccessTokenEntity> findAllByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(UUID userId);

    /**
     * Live tokens (unrevoked and unexpired) of a user, counted against the per-user quota.
     */
    @Query("SELECT COUNT(t) FROM PersonalAccessTokenEntity t WHERE t.user.id = :userId AND t.revokedAt IS NULL "
            + "AND (t.expiresAt IS NULL OR t.expiresAt > :now)")
    long countLiveTokens(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Revokes a token, scoped to its owner so another user's token id never matches (IDOR).
     * Returns 0 when the token does not exist, belongs to someone else or is already revoked.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PersonalAccessTokenEntity t SET t.revokedAt = :now, t.updatedAt = :now "
            + "WHERE t.id = :id AND t.user.id = :userId AND t.revokedAt IS NULL")
    int revoke(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Revokes every unrevoked token of a user (e.g. after a password reset).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PersonalAccessTokenEntity t SET t.revokedAt = :now, t.updatedAt = :now "
            + "WHERE t.user.id = :userId AND t.revokedAt IS NULL")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Records usage at most once per interval: the row is only written when the stored value is older than
     * {@code staleBefore}, so a busy MCP client does not cause a write per request.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PersonalAccessTokenEntity t SET t.lastUsedAt = :now "
            + "WHERE t.id = :id AND (t.lastUsedAt IS NULL OR t.lastUsedAt < :staleBefore)")
    int touchLastUsed(@Param("id") UUID id, @Param("now") Instant now, @Param("staleBefore") Instant staleBefore);

    /**
     * Purges tokens revoked or expired before the cutoff.
     */
    @Modifying
    @Query("DELETE FROM PersonalAccessTokenEntity t WHERE t.revokedAt < :cutoff OR t.expiresAt < :cutoff")
    int deleteStaleTokens(@Param("cutoff") Instant cutoff);
}
