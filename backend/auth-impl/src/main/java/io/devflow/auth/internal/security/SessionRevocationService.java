package io.devflow.auth.internal.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.devflow.auth.internal.passwordreset.PasswordResetProperties;
import io.devflow.auth.internal.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Decides whether a JWT was signed out by a later password change (T-008).
 *
 * <p>JWTs are stateless, so "revoke every session" is expressed as: reject tokens whose {@code iat} is before
 * {@code users.password_changed_at}. Access-token checks go through a short-lived per-instance cache so the
 * JWT filter does not hit the database on every request.
 */
@Component
public class SessionRevocationService {

    private static final long MAX_CACHED_USERS = 100_000;

    private final UserRepository userRepository;
    private final Cache<UUID, Optional<Instant>> passwordChangedAt;

    public SessionRevocationService(UserRepository userRepository, PasswordResetProperties properties) {
        this.userRepository = userRepository;
        this.passwordChangedAt = Caffeine.newBuilder()
                .maximumSize(MAX_CACHED_USERS)
                .expireAfterWrite(properties.getSessionCheckCacheTtl())
                .build();
    }

    /**
     * True when the token was issued before the user's last password change. Uses the cache.
     */
    public boolean isRevoked(UUID userId, Instant issuedAt) {
        return issuedBefore(issuedAt,
                passwordChangedAt.get(userId, id -> userRepository.findPasswordChangedAt(id)));
    }

    /**
     * Drops the cached value, e.g. right after this instance changed the user's password.
     */
    public void evict(UUID userId) {
        passwordChangedAt.invalidate(userId);
    }

    /**
     * {@code iat} has second precision, so the change instant is truncated to seconds: a token issued in the same
     * second as the reset (the post-reset login) stays valid.
     */
    public static boolean issuedBefore(Instant issuedAt, Optional<Instant> changedAt) {
        if (changedAt.isEmpty()) {
            return false;
        }
        return issuedAt == null || issuedAt.isBefore(changedAt.get().truncatedTo(ChronoUnit.SECONDS));
    }
}
