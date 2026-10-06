package io.devflow.auth.internal.verification;

import java.time.Instant;
import java.util.UUID;

/**
 * Module-internal event asking for a verification email to be delivered once the issuing
 * transaction commits. Not a {@code DevFlowEvent}: it never leaves the auth module.
 *
 * <p>Carries the raw token, so {@link #toString()} is overridden to keep it out of logs.
 */
public record VerificationEmailRequestedEvent(
        UUID userId,
        String email,
        String fullName,
        String rawToken,
        Instant expiresAt
) {

    @Override
    public String toString() {
        return "VerificationEmailRequestedEvent[userId=" + userId + ", expiresAt=" + expiresAt + "]";
    }
}
