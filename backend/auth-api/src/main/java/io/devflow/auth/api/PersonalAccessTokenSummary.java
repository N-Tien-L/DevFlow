package io.devflow.auth.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Owner's view of a personal access token. Never carries the token itself, only its public prefix.
 */
public record PersonalAccessTokenSummary(
        UUID id,
        String name,
        String tokenPrefix,
        Instant expiresAt,
        Instant lastUsedAt,
        Instant createdAt,
        boolean expired
) {}
