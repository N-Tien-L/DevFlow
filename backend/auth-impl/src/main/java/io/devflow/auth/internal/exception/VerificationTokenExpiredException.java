package io.devflow.auth.internal.exception;

/**
 * Thrown when a verification token is genuine but past its expiry time.
 */
public class VerificationTokenExpiredException extends RuntimeException {

    public VerificationTokenExpiredException() {
        super("Verification token expired. Please request a new one.");
    }
}
