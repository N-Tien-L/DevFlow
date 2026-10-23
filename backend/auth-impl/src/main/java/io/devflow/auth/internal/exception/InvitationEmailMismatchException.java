package io.devflow.auth.internal.exception;

/**
 * Thrown when an invitation is accepted by an account whose email differs from the invited address.
 */
public class InvitationEmailMismatchException extends RuntimeException {

    public InvitationEmailMismatchException() {
        super("This invitation was sent to a different email address. Sign in with the invited account.");
    }
}
