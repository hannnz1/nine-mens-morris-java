package io.github.hannnz1.morris.backend.api;

import io.github.hannnz1.morris.backend.api.GameApiDtos.ApiError;
import io.github.hannnz1.morris.backend.api.GameApiDtos.FieldError;
import io.github.hannnz1.morris.engine.GameRuleException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApi(ApiException exception, HttpServletRequest request) {
        return response(exception.status(), exception.code(), exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(GameRuleException.class)
    ResponseEntity<ApiError> handleRule(GameRuleException exception, HttpServletRequest request) {
        return response(HttpStatus.UNPROCESSABLE_ENTITY, "GAME_RULE_VIOLATION",
                exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception,
                                               HttpServletRequest request) {
        List<FieldError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "The request is invalid", request, errors);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException exception,
                                                  HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "MISSING_REQUIRED_HEADER",
                exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException exception,
                                                   HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "MALFORMED_JSON",
                "The request body is not valid JSON or contains an unsupported value",
                request, List.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleInvalidParameter(MethodArgumentTypeMismatchException exception,
                                                     HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                "A path or query parameter has an invalid value", request, List.of());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiError> handleOptimisticLock(ObjectOptimisticLockingFailureException exception,
                                                   HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                "The game changed while this action was being processed; reload and retry",
                request, List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleConflict(DataIntegrityViolationException exception,
                                             HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "CONCURRENT_REQUEST_CONFLICT",
                "A concurrent request has already been processed; reload before retrying",
                request, List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error("Unexpected request failure for {}", request.getRequestURI(), exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred", request, List.of());
    }

    private ResponseEntity<ApiError> response(HttpStatus status, String code, String message,
                                               HttpServletRequest request, List<FieldError> errors) {
        ApiError body = new ApiError(Instant.now(), status.value(), code, message,
                request.getRequestURI(), errors);
        return ResponseEntity.status(status).body(body);
    }
}
