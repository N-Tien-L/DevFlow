package io.devflow.auth.internal.exception;

/**
 * Thrown when a workspace operation conflicts with existing state or a quota
 * (e.g. workspace limit reached, invitation already pending). Mapped to HTTP 409 with a machine-readable code.
 */
public class WorkspaceConflictException extends RuntimeException {

    private final String code;

    public WorkspaceConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
