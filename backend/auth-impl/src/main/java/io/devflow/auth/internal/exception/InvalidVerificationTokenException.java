package io.devflow.auth.internal.exception;

/**
 * Thrown when a verification token is unknown or has already been consumed.
 */
public class InvalidVerificationTokenException extends RuntimeException {

    public InvalidVerificationTokenException() {
        super("Invalid or already used verification token");
    }
}
