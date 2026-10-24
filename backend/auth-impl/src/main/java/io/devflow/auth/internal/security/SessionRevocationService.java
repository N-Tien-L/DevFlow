package io.devflow.auth.internal.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.devflow.auth.internal.passwordreset.PasswordResetProperties;
import io.devflow.auth.internal.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Decides whether a JWT was signed out by a later password change (T-008).
 *
 * <p>Every access and refresh token carries the user's {@code token_version} (claim {@code tv}); a password reset
 * increments it. A token is revoked as soon as its version differs from the stored one: an exact comparison, with
 * no dependence on issue times. Access-token and STOMP checks go through a short-lived per-instance cache so the
 * JWT filter does not hit the database on every request; refresh compares against the database directly.
 */
@Component
public class SessionRevocationService {

    private static final long MAX_CACHED_USERS = 100_000;

    private final UserRepository userRepository;
    private final Cache<UUID, Optional<Integer>> tokenVersions;

    public SessionRevocationService(UserRepository userRepository, PasswordResetProperties properties) {
        this.userRepository = userRepository;
        this.tokenVersions = Caffeine.newBuilder()
                .maximumSize(MAX_CACHED_USERS)
                .expireAfterWrite(properties.getSessionCheckCacheTtl())
                .build();
    }

    /**
     * True when the token's version no longer matches the user's, or the user no longer exists (account deleted,
     * T-009). Uses the cache.
     */
    public boolean isRevoked(UUID userId, int tokenVersion) {
        return tokenVersions.get(userId, userRepository::findTokenVersion)
                .map(current -> current != tokenVersion)
                .orElse(true);
    }

    /**
     * Drops the cached value, e.g. right after this instance changed the user's password or deleted the account.
     */
    public void evict(UUID userId) {
        tokenVersions.invalidate(userId);
    }
}
