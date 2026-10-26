package io.devflow.ai.internal.exception;

import static io.devflow.ai.internal.exception.AiExceptionHandler.problem;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * RFC 7807 errors for framework exceptions raised by AI controllers, scoped to this module so it cannot
 * change other modules' responses. Runs after {@link AiExceptionHandler}, which owns the AI exception
 * types; this advice's catch-all would otherwise shadow them.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RestControllerAdvice(basePackages = "io.devflow.ai.internal")
public class AiApiControllerAdvice {

    private static final Logger log = LoggerFactory.getLogger(AiApiControllerAdvice.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Validation failed for one or more fields.", request);
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ProblemDetail> handleUnreadableBody(
            Exception ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "The request is malformed or contains an invalid value.", request));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return frameworkResponse(ex, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        if (ex instanceof ErrorResponse error) {
            return frameworkResponse(error, request);
        }
        log.error("Unexpected AI API error path={} exceptionType={}", request.getRequestURI(),
                ex.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected internal error occurred. Please try again later.", request));
    }

    /** Same sanitized framework response contract as the application-wide advice. */
    private static ResponseEntity<ProblemDetail> frameworkResponse(ErrorResponse error, HttpServletRequest request) {
        HttpStatusCode status = error.getStatusCode();
        HttpStatus known = HttpStatus.resolve(status.value());
        if (status.is5xxServerError()) {
            log.error("AI framework request failed status={} exceptionType={}", status.value(),
                    error.getClass().getSimpleName());
        }
        String detail = switch (status.value()) {
            case 404 -> "The requested resource does not exist.";
            case 405 -> "The request method is not supported for this resource.";
            case 406 -> "The requested response media type is not supported.";
            case 415 -> "The request content type is not supported.";
            default -> status.is4xxClientError() ? "The request is invalid."
                    : "An unexpected internal error occurred. Please try again later.";
        };
        return ResponseEntity.status(status).headers(error.getHeaders()).body(
                problem(status, known == null ? "REQUEST_FAILED" : known.name(), detail, request));
    }
}
