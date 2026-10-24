package io.devflow.auth.internal.passwordreset;

import java.time.Instant;
import java.util.UUID;

/**
 * Module-internal event asking for a password reset link to be emailed once the issuing transaction commits.
 * Not a {@code DevFlowEvent}: it never leaves the auth module.
 *
 * <p>Carries the raw token and the email, so {@link #toString()} is overridden to keep them out of logs.
 */
public record PasswordResetEmailRequestedEvent(
        UUID userId,
        String email,
        String fullName,
        String rawToken,
        Instant expiresAt
) {

    @Override
    public String toString() {
        return "PasswordResetEmailRequestedEvent[userId=" + userId + ", expiresAt=" + expiresAt + "]";
    }
}
