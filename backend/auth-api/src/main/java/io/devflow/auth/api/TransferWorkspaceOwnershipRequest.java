package io.devflow.auth.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request payload for handing a workspace to another member. The new owner must already be a member;
 * the previous owner stays on as {@code ADMIN}.
 */
public record TransferWorkspaceOwnershipRequest(
        @NotNull(message = "New owner is required")
        UUID newOwnerId
) {}
