package io.devflow.auth.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Summary view of a workspace and the current user's membership role within it.
 */
public record WorkspaceSummary(
        UUID id,
        String name,
        String slug,
        String role,
        Instant createdAt
) {}
