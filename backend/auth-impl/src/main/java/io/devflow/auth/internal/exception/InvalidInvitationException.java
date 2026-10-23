package io.devflow.auth.internal.exception;

/**
 * Thrown when an invitation token is unknown, already accepted or revoked.
 */
public class InvalidInvitationException extends RuntimeException {

    public InvalidInvitationException() {
        super("Invalid, revoked or already used invitation");
    }
}
