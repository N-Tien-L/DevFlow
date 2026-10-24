package io.devflow.auth.internal.passwordreset;

import java.time.Instant;
import java.util.UUID;

/**
 * Module-internal event asking for the "your password was changed" notice once the reset commits.
 * {@link #toString()} keeps the email out of logs.
 */
public record PasswordChangedEmailRequestedEvent(
        UUID userId,
        String email,
        String fullName,
        Instant changedAt
) {

    @Override
    public String toString() {
        return "PasswordChangedEmailRequestedEvent[userId=" + userId + ", changedAt=" + changedAt + "]";
    }
}
