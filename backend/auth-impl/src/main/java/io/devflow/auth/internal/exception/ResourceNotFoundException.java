package io.devflow.auth.internal.exception;

/**
 * Thrown when a requested resource (user, workspace, etc.) is not found.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
