package io.devflow.auth.internal.exception;

/**
 * Thrown when a genuine password reset token is past its expiry.
 */
public class PasswordResetTokenExpiredException extends RuntimeException {

    public PasswordResetTokenExpiredException() {
        super("This password reset link has expired. Request a new one.");
    }
}
