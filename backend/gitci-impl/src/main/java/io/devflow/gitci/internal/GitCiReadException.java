package io.devflow.gitci.internal;

import org.springframework.http.HttpStatus;

/** Controlled domain error raised by authenticated GitCI read endpoints. */
final class GitCiReadException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Long retryAfterSeconds;

    private GitCiReadException(HttpStatus status, String code, String message, Long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }

    Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    static GitCiReadException authenticationRequired() {
        return new GitCiReadException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
                "Authentication is required to access Git & CI data.", null);
    }

    static GitCiReadException workspaceNotFound() {
        return new GitCiReadException(HttpStatus.NOT_FOUND, "WORKSPACE_NOT_FOUND",
                "The requested workspace does not exist or is not accessible.", null);
    }

    static GitCiReadException validationFailed(String message) {
        return new GitCiReadException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, null);
    }

    static GitCiReadException rateLimited(long retryAfterSeconds) {
        return new GitCiReadException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED",
                "Too many Git & CI read requests. Please retry shortly.", retryAfterSeconds);
    }

    static GitCiReadException unavailable() {
        return new GitCiReadException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "Git & CI read service is temporarily unavailable.", null);
    }
}
