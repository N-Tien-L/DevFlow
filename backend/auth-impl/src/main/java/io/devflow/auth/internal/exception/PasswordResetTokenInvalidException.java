package io.devflow.auth.internal.exception;

/**
 * Thrown when a password reset token is unknown, already used or superseded by a newer one.
 */
public class PasswordResetTokenInvalidException extends RuntimeException {

    public PasswordResetTokenInvalidException() {
        super("Invalid or already used password reset link. Request a new one.");
    }
}
