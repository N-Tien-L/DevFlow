package io.devflow.board.internal;

import org.springframework.http.HttpStatus;

/** Safe, typed error returned by the Board REST API. */
public final class BoardApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Long retryAfterSeconds;

    private BoardApiException(HttpStatus status, String code, String message, Long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static BoardApiException authenticationRequired() {
        return new BoardApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
                "A valid access token is required.", null);
    }

    public static BoardApiException forbidden() {
        return new BoardApiException(HttpStatus.FORBIDDEN, "WORKSPACE_ACCESS_DENIED",
                "You do not have access to this workspace.", null);
    }

    public static BoardApiException notFound() {
        return new BoardApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                "The requested resource was not found.", null);
    }

    public static BoardApiException badRequest(String code, String detail) {
        return new BoardApiException(HttpStatus.BAD_REQUEST, code, detail, null);
    }

    public static BoardApiException conflict(String code, String detail) {
        return new BoardApiException(HttpStatus.CONFLICT, code, detail, null);
    }

    public static BoardApiException unavailable() {
        return new BoardApiException(HttpStatus.SERVICE_UNAVAILABLE, "PERMISSION_CHECK_UNAVAILABLE",
                "Workspace access could not be verified. Please try again.", null);
    }

    public static BoardApiException rateLimited(long retryAfterSeconds) {
        return new BoardApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED",
                "Too many board changes. Please try again later.", retryAfterSeconds);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
