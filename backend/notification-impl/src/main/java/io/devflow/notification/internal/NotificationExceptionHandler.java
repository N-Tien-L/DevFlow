package io.devflow.notification.internal;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = NotificationController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NotificationExceptionHandler {
    @ExceptionHandler(NotificationException.class)
    public ResponseEntity<ProblemDetail> handle(NotificationException exception, HttpServletRequest request) {
        var response = ResponseEntity.status(exception.status());
        if (exception.retryAfter() != null) { response.header("Retry-After", exception.retryAfter().toString()); }
        return response.body(problem(exception.status(), exception.getMessage(), request));
    }

    @ExceptionHandler({HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ProblemDetail> invalid(Exception exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(problem(400, "Invalid notification request parameters.", request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception exception, HttpServletRequest request) {
        org.slf4j.LoggerFactory.getLogger(getClass()).error("Notification API failed exceptionType={}",
                exception.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(problem(500, "Notification service unavailable.", request));
    }

    private static ProblemDetail problem(int status, String detail, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        String correlationId = org.slf4j.MDC.get("correlationId");
        if (correlationId != null) { problem.setProperty("correlationId", correlationId); }
        return problem;
    }
}
