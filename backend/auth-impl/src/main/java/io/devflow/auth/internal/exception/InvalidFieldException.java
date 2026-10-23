package io.devflow.auth.internal.exception;

/**
 * Thrown when a field passes Bean Validation but is rejected by server-side business sanitisation
 * (e.g. a display name that is empty once HTML is stripped). Rendered like a validation error.
 */
public class InvalidFieldException extends RuntimeException {

    private final String field;

    public InvalidFieldException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
