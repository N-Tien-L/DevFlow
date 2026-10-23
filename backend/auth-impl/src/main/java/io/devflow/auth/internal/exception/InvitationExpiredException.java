package io.devflow.auth.internal.exception;

/**
 * Thrown when a genuine invitation token is past its expiry.
 */
public class InvitationExpiredException extends RuntimeException {

    public InvitationExpiredException() {
        super("This invitation has expired. Ask a workspace admin to send a new one.");
    }
}
