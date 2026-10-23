package io.devflow.auth.internal.exception;

/**
 * Thrown when an account with valid credentials has not completed email verification yet.
 */
public class AccountNotVerifiedException extends RuntimeException {

    public AccountNotVerifiedException() {
        super("Email verification required. Please check your inbox or request a new verification email.");
    }
}
