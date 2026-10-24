package io.devflow.ai.internal.exception;

import org.springframework.http.HttpStatus;

/** Safe, typed error returned by the AI REST API; message and code are fixed strings shown to clients. */
public final class AiApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Long retryAfterSeconds;

    private AiApiException(HttpStatus status, String code, String message, Long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static AiApiException authenticationRequired() {
        return new AiApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
                "A valid access token is required.", null);
    }

    public static AiApiException forbidden() {
        return new AiApiException(HttpStatus.FORBIDDEN, "WORKSPACE_ACCESS_DENIED",
                "You do not have access to this workspace.", null);
    }

    public static AiApiException badRequest(String code, String detail) {
        return new AiApiException(HttpStatus.BAD_REQUEST, code, detail, null);
    }

    public static AiApiException rateLimited(long retryAfterSeconds) {
        return new AiApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_RATE_LIMITED",
                "Too many AI requests. Please wait before trying again.", retryAfterSeconds);
    }

    /** The model kept answering in an unusable shape; nothing partial is returned. */
    public static AiApiException outputInvalid() {
        return new AiApiException(HttpStatus.BAD_GATEWAY, "AI_OUTPUT_INVALID",
                "The AI assistant returned an unusable answer. Please try again or rephrase the request.", null);
    }

    public static AiApiException membershipCheckUnavailable() {
        return new AiApiException(HttpStatus.SERVICE_UNAVAILABLE, "MEMBERSHIP_CHECK_UNAVAILABLE",
                "Workspace access could not be verified. Please try again later.", null);
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
