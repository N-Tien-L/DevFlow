package io.devflow.ai.internal.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 7807 mapping for AI module failures. Ordered ahead of the application-wide advice, whose
 * catch-all {@code Exception} handler would otherwise turn these into 500s. Provider details
 * (status codes, response bodies) are never included.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AiExceptionHandler {

    @ExceptionHandler(AiProviderUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleProviderUnavailable(
            AiProviderUnavailableException ex, HttpServletRequest request) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        problem.setTitle("Service Unavailable");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "AI_PROVIDER_UNAVAILABLE");
        problem.setProperty("timestamp", Instant.now().toString());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(problem);
    }

    @ExceptionHandler(AiPromptRejectedException.class)
    public ResponseEntity<ProblemDetail> handlePromptRejected(
            AiPromptRejectedException ex, HttpServletRequest request) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Bad Request");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", ex.getCode());
        problem.setProperty("timestamp", Instant.now().toString());

        return ResponseEntity.badRequest().body(problem);
    }
}
