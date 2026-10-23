package io.devflow.auth.internal.exception;

/** A bounded member directory query was malformed. */
public final class InvalidWorkspaceMemberQueryException extends RuntimeException {
    public InvalidWorkspaceMemberQueryException() {
        super("Invalid workspace member search or page.");
    }
}
