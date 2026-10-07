package io.devflow.board.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** RFC 7807 errors scoped to Board controllers; takes precedence over Auth's fallback advice. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = BoardController.class)
public class BoardApiControllerAdvice {

    private static final Logger log = LoggerFactory.getLogger(BoardApiControllerAdvice.class);

    @ExceptionHandler(BoardApiException.class)
    public ResponseEntity<ProblemDetail> handleBoardException(BoardApiException exception, HttpServletRequest request) {
        ProblemDetail problem = problem(exception.status(), exception.code(), exception.getMessage(), request);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(exception.status());
        if (exception.retryAfterSeconds() != null) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()));
            problem.setProperty("retryAfterSeconds", exception.retryAfterSeconds());
        }
        return response.body(problem);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ProblemDetail> handleValidation(Exception exception, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Validation failed for one or more fields.", request);
        Map<String, String> errors = new LinkedHashMap<>();
        if (exception instanceof BindException bindException) {
            for (FieldError error : bindException.getBindingResult().getFieldErrors()) {
                errors.put(error.getField(), error.getDefaultMessage());
            }
        }
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "One or more request parameters are invalid.", request);
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    public ResponseEntity<ProblemDetail> handleMalformedRequest(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "The request is malformed or contains an invalid value.", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "Content-Type must be application/json.", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintFailure(
            DataIntegrityViolationException exception, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "DATA_CONFLICT",
                "The requested change conflicts with the current board data.", request);
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleBoardBusy(
            PessimisticLockingFailureException exception, HttpServletRequest request) {
        log.warn("Board mutation lock was not acquired; correlationId={}", BoardRequestContext.correlationId());
        return response(HttpStatus.CONFLICT, "BOARD_BUSY",
                "The board is being updated. Refresh the board and try again.", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unexpected Board API error; correlationId={}, exceptionType={}",
                BoardRequestContext.correlationId(), exception.getClass().getSimpleName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected internal error occurred. Please try again later.", request);
    }

    private static ResponseEntity<ProblemDetail> response(
            HttpStatus status, String code, String detail, HttpServletRequest request) {
        return ResponseEntity.status(status).body(problem(status, code, detail, request));
    }

    private static ProblemDetail problem(
            HttpStatus status, String code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://devflow.local/problems/" + code.toLowerCase().replace('_', '-')));
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("correlationId", BoardRequestContext.correlationId());
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }
}
