package io.devflow.auth.internal.exception;

/**
 * Thrown when the caller lacks the workspace role an operation requires. The same response is used
 * whether the workspace does not exist or the caller is not a member, so ids cannot be probed.
 */
public class WorkspacePermissionDeniedException extends RuntimeException {

    public WorkspacePermissionDeniedException(String message) {
        super(message);
    }
}
