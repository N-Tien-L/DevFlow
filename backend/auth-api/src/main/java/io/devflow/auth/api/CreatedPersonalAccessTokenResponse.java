package io.devflow.auth.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Response to token creation. {@code token} is the only time the raw token is ever returned;
 * {@link #toString()} hides it so it cannot reach logs.
 */
public record CreatedPersonalAccessTokenResponse(
        UUID id,
        String name,
        String token,
        String tokenPrefix,
        Instant expiresAt,
        Instant createdAt
) {

    @Override
    public String toString() {
        return "CreatedPersonalAccessTokenResponse[id=" + id + ", tokenPrefix=" + tokenPrefix + "]";
    }
}
