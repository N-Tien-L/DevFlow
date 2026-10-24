package io.devflow.ai.internal.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 7807 mapping for AI module failures. Ordered ahead of the application-wide advice, whose
 * catch-all {@code Exception} handler would otherwise turn these into 500s. Provider details
 * (status codes, response bodies) are never included. Framework errors raised by AI controllers
 * (validation, malformed bodies) are mapped by {@link AiApiControllerAdvice}.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AiExceptionHandler {

    @ExceptionHandler(AiApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(AiApiException ex, HttpServletRequest request) {
        ProblemDetail problem = problem(ex.status(), ex.code(), ex.getMessage(), request);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.status());
        if (ex.retryAfterSeconds() != null) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()));
            problem.setProperty("retryAfterSeconds", ex.retryAfterSeconds());
        }
        return response.body(problem);
    }

    @ExceptionHandler(AiProviderUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleProviderUnavailable(
            AiProviderUnavailableException ex, HttpServletRequest request) {

        ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, "AI_PROVIDER_UNAVAILABLE", ex.getMessage(),
                request);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(problem);
    }

    @ExceptionHandler(AiPromptRejectedException.class)
    public ResponseEntity<ProblemDetail> handlePromptRejected(
            AiPromptRejectedException ex, HttpServletRequest request) {

        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, ex.getCode(), ex.getMessage(), request));
    }

    static ProblemDetail problem(HttpStatusCode status, String code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        HttpStatus known = HttpStatus.resolve(status.value());
        problem.setTitle(known == null ? "Error" : known.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }
}
