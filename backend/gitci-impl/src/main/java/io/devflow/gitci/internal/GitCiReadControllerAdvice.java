package io.devflow.gitci.internal;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** RFC 7807 ProblemDetail advice strictly scoped to GitCiReadController. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = GitCiReadController.class)
class GitCiReadControllerAdvice {

    private static final Logger log = LoggerFactory.getLogger(GitCiReadControllerAdvice.class);
    private final MeterRegistry metrics;

    GitCiReadControllerAdvice(MeterRegistry metrics) {
        this.metrics = metrics;
    }

    @ExceptionHandler(GitCiReadException.class)
    ResponseEntity<ProblemDetail> handleReadException(GitCiReadException exception, HttpServletRequest request) {
        if (exception.status() == HttpStatus.BAD_REQUEST) {
            recordInvalid(request);
        }
        ProblemDetail detail = problem(exception.status(), exception.code(), exception.getMessage(), request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(exception.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (exception.retryAfterSeconds() != null) {
            builder.header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()));
            detail.setProperty("retryAfterSeconds", exception.retryAfterSeconds());
        }
        return builder.body(detail);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        recordInvalid(request);
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "One or more query parameters are invalid.", request);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ProblemDetail> handleMalformedRequest(Exception ex, HttpServletRequest request) {
        recordInvalid(request);
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "The request path or query parameters contain an invalid value.", request);
    }

    private void recordInvalid(HttpServletRequest request) {
        try {
            String uri = request.getRequestURI();
            String endpoint = uri != null && uri.endsWith("/activity") ? "task_activity" : "task_summaries";
            metrics.counter("devflow.gitci.read.requests",
                    "endpoint", endpoint, "outcome", "invalid", "status", "400").increment();
        } catch (RuntimeException ignored) {
            // Metric failure must never alter error response.
        }
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        if (exception instanceof ErrorResponse error) {
            HttpStatusCode status = error.getStatusCode();
            HttpStatus known = HttpStatus.resolve(status.value());
            String detail = status.is4xxClientError()
                    ? "The request could not be processed."
                    : "An unexpected internal error occurred. Please try again later.";
            return ResponseEntity.status(status)
                    .headers(error.getHeaders())
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(problem(status, known == null ? "REQUEST_FAILED" : known.name(), detail, request));
        }
        log.error("Unexpected GitCI read API error; correlationId={} exceptionType={}",
                correlationId(), exception.getClass().getSimpleName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected internal error occurred. Please try again later.", request);
    }

    private static ResponseEntity<ProblemDetail> response(
            HttpStatus status, String code, String detail, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem(status, code, detail, request));
    }

    private static ProblemDetail problem(
            HttpStatusCode status, String code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://devflow.local/problems/" + code.toLowerCase().replace('_', '-')));
        HttpStatus known = HttpStatus.resolve(status.value());
        problem.setTitle(known == null ? "Error" : known.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("correlationId", correlationId());
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    private static String correlationId() {
        String existing = MDC.get("correlationId");
        return (existing != null && !existing.isBlank()) ? existing : UUID.randomUUID().toString();
    }
}
