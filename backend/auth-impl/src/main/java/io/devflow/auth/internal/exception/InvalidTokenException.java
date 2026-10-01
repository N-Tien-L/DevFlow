package io.devflow.auth.internal.exception;

/**
 * Thrown when a JWT or refresh token is invalid, expired, malformed, or of the wrong type.
 */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super(message);
    }

    public InvalidTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
