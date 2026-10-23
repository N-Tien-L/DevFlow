package io.devflow.auth.api;

import java.time.Instant;
import java.util.UUID;

/**
 * View of a workspace invitation for the workspace's owners and admins. Never carries the token.
 */
public record WorkspaceInvitationSummary(
        UUID id,
        UUID workspaceId,
        String email,
        String role,
        String status,
        UUID invitedBy,
        Instant expiresAt,
        Instant createdAt
) {}
