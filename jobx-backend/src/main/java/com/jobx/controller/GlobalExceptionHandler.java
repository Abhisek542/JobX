package com.jobx.controller;

import com.jobx.dto.ApiError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps every exception to the ApiError contract so the frontend never sees a
 * Spring-default body. Handling errors here (instead of the container's /error
 * forward) also sidesteps the forward-to-/error gotcha documented in CLAUDE.md,
 * though /error stays permitted in SecurityConfig as a safety net.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String code = status.name().toLowerCase(Locale.ROOT);
        return ResponseEntity.status(status)
                .body(ApiError.of(status.value(), code, ex.getReason()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(error.getField(),
                    error.getDefaultMessage() != null ? error.getDefaultMessage() : "invalid value");
        }
        return ResponseEntity.badRequest()
                .body(new ApiError(400, "validation_failed", "request body failed validation", fieldErrors));
    }

    // Malformed JSON, or a body value that can't bind — e.g. an unknown
    // AtsPlatform / status enum constant.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(400, "malformed_request",
                        "request body is malformed or contains an invalid value"));
    }

    // Bad path/query parameter type — e.g. a non-UUID {id}.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(400, "invalid_parameter",
                        "parameter '" + ex.getName() + "' has an invalid value"));
    }

    // Unique-constraint fallback for races the controllers' own checks miss.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleConflict(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(409, "conflict", "resource already exists"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        // Full detail server-side only — never leak internals to the client
        log.error("Unhandled exception", ex);
        return ResponseEntity.internalServerError()
                .body(ApiError.of(500, "internal_error", "something went wrong"));
    }
}
