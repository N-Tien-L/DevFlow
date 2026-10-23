package io.devflow.auth.internal.workspace;

import java.time.Instant;
import java.util.UUID;

/**
 * Module-internal event asking for a workspace invitation email to be delivered once the issuing
 * transaction commits. Not a {@code DevFlowEvent}: it never leaves the auth module.
 *
 * <p>Carries the raw token and the invitee email, so {@link #toString()} is overridden to keep them out of logs.
 */
public record InvitationEmailRequestedEvent(
        UUID invitationId,
        String email,
        String inviterName,
        String workspaceName,
        String role,
        String rawToken,
        Instant expiresAt
) {

    @Override
    public String toString() {
        return "InvitationEmailRequestedEvent[invitationId=" + invitationId + ", expiresAt=" + expiresAt + "]";
    }
}
