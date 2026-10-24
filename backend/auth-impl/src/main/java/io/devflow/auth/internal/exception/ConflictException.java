package io.devflow.auth.internal.exception;

/**
 * Thrown when an operation conflicts with existing state or a quota
 * (e.g. workspace or token limit reached, invitation already pending). Mapped to HTTP 409 with a machine-readable code.
 */
public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
