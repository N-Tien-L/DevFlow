package io.devflow.auth.internal.exception;

/**
 * Thrown when attempting to register an account with an email that is already in use.
 */
public class EmailAlreadyExistsException extends RuntimeException {

    public EmailAlreadyExistsException() {
        super("An account with this email already exists");
    }
}
