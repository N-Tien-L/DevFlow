package io.devflow.common.security;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Verified identity metadata bound to a STOMP session by the Auth module. */
public record AuthenticatedSession(UUID userId, Instant expiresAt) {

    /** Server-only STOMP session attribute shared with transport interceptors. */
    public static final String STOMP_SESSION_ATTRIBUTE = AuthenticatedSession.class.getName();

    public AuthenticatedSession {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }
}
