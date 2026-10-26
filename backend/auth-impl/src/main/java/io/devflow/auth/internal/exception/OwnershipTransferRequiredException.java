package io.devflow.auth.internal.exception;

import java.util.List;
import java.util.UUID;

/**
 * Thrown when an account cannot be deleted because it still owns workspaces that have other members.
 * Mapped to 409 with the workspaces to hand over and the endpoint to do it.
 */
public class OwnershipTransferRequiredException extends RuntimeException {

    private final List<BlockingWorkspace> workspaces;

    public OwnershipTransferRequiredException(List<BlockingWorkspace> workspaces) {
        super("You still own workspaces with other members. Transfer ownership of each to another member "
                + "(POST /api/v1/auth/workspaces/{workspaceId}/transfer-ownership), then delete your account.");
        this.workspaces = List.copyOf(workspaces);
    }

    public List<BlockingWorkspace> getWorkspaces() {
        return workspaces;
    }

    /**
     * A workspace that blocks the deletion: the caller owns it and {@code otherMembers} others belong to it.
     */
    public record BlockingWorkspace(UUID id, String name, long otherMembers) {
    }
}
