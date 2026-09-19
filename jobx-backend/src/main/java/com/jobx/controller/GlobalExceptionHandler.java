package com.jobx.controller;

import com.jobx.dto.ApiError;
import com.jobx.resolve.SafeUrlFetcher;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps every exception to the ApiError contract so the frontend never sees a
 * Spring-default body. Handling errors here (instead of the container's /error
 * forward) also sidesteps the forward-to-/error gotcha documented in CLAUDE.md,
 * though /error stays permitted in SecurityConfig as a safety net.
 *
 * One rule when adding to this class: an exception class may appear in exactly
 * one @ExceptionHandler list here. A duplicate is an IllegalStateException
 * ("Ambiguous @ExceptionHandler method mapped for ...") at context refresh —
 * which is also why this advice must never extend ResponseEntityExceptionHandler:
 * its handleException already claims MethodArgumentNotValidException and
 * HttpMessageNotReadableException, both handled below.
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

    /**
     * A careers URL Jobx will not request at all — a non-HTTP scheme, or a host
     * that resolves somewhere off the public internet. 400 rather than 500
     * because the user pasted it and the user can change it, and the message is
     * SafeUrlFetcher's own: it says what was wrong with the address without
     * describing what is reachable from the server.
     */
    @ExceptionHandler(SafeUrlFetcher.UnsafeUrlException.class)
    public ResponseEntity<ApiError> handleUnsafeUrl(SafeUrlFetcher.UnsafeUrlException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(400, "invalid_url", ex.getMessage()));
    }

    // Unique-constraint fallback for races the controllers' own checks miss.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleConflict(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(409, "conflict", "resource already exists"));
    }

    /**
     * Requests Spring itself rejects before, or instead of, a controller: an
     * unknown path, a wrong method, a missing request value, a body or Accept
     * header we can't speak. Without this they fell through to handleUnexpected
     * and answered 500 internal_error with a stack trace in the log
     * (BUG_REPORT #7) — a lie to the caller, and noise for us.
     *
     * Every type listed implements Spring's ErrorResponse, which already knows
     * the right status and the right response headers (Allow on a 405, Accept
     * on a 415), so this method maps rather than invents. The parameter is
     * typed ErrorResponse to say so; Spring binds the thrown exception to it by
     * Class.isInstance (AnnotatedMethod.findProvidedArgument), which runs before
     * any argument resolver. The annotation still has to list concrete classes,
     * because the value() of @ExceptionHandler is Class&lt;? extends Throwable&gt;[]
     * and an interface is not a Throwable.
     *
     * Both 404 shapes are listed on purpose: with Boot's static resource handler
     * mapped (the default) an unmapped path reaches ResourceHttpRequestHandler
     * and throws NoResourceFoundException; with spring.web.resources.add-mappings
     * false, DispatcherServlet throws NoHandlerFoundException instead. Handling
     * both makes the contract independent of that property.
     *
     * Nothing here can shadow handleUnreadable or handleTypeMismatch:
     * HttpMessageNotReadableException and MethodArgumentTypeMismatchException are
     * not ErrorResponses and are not subclasses of anything listed, so they never
     * enter the match set (ExceptionDepthComparator walks superclasses only).
     */
    @ExceptionHandler({
            NoResourceFoundException.class,                  // 404 — unmapped path, resource handler on
            NoHandlerFoundException.class,                   // 404 — unmapped path, resource handler off
            HttpRequestMethodNotSupportedException.class,    // 405 — carries Allow
            HttpMediaTypeNotSupportedException.class,        // 415 — carries Accept
            HttpMediaTypeNotAcceptableException.class,       // 406
            ServletRequestBindingException.class             // 400 — missing @RequestParam/header/cookie
    })
    public ResponseEntity<ApiError> handleFrameworkRejection(ErrorResponse ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());

        // Default the slug to the status name, the same rule handleResponseStatus
        // uses, so one status never has two slugs. Override only where a more
        // specific code tells the frontend something the status does not.
        String code = status.name().toLowerCase(Locale.ROOT);
        String detail;

        if (ex instanceof NoResourceFoundException || ex instanceof NoHandlerFoundException) {
            // Deliberately NOT ex.getMessage(): that reads "No static resource
            // watchlist/nope." — it reflects the caller's path back at them and
            // advertises that the request fell through to the static resource
            // handler, which means nothing for a JSON API.
            detail = "no endpoint for this path";
        } else if (ex instanceof HttpRequestMethodNotSupportedException) {
            detail = "method not allowed for this path";   // the Allow header carries the answer
        } else if (ex instanceof MissingServletRequestParameterException missing) {
            code = "missing_parameter";
            // The name is the controller's own declared parameter, not caller
            // input — same shape as handleTypeMismatch's invalid_parameter.
            detail = "parameter '" + missing.getParameterName() + "' is required";
        } else if (ex instanceof ServletRequestBindingException) {
            code = "missing_parameter";
            detail = "a required request value is missing";
        } else if (ex instanceof HttpMediaTypeNotSupportedException) {
            detail = "unsupported content type — send application/json";
        } else if (ex instanceof HttpMediaTypeNotAcceptableException) {
            detail = "this endpoint can only produce application/json";
        } else {
            detail = "the request could not be handled";
        }

        // Client error, not a server fault: one line, no stack trace. The full
        // exception is of no diagnostic value — the status and path are the story.
        log.warn("Rejected {} {} -> {} {}", request.getMethod(), request.getRequestURI(),
                status.value(), code);

        return ResponseEntity.status(status)
                .headers(ex.getHeaders())   // Allow on 405, Accept on 415 — Spring already built them
                .body(ApiError.of(status.value(), code, detail));
    }

    /**
     * A path variable the controller declares but the mapping cannot supply is a
     * bug in our own routing, not a client mistake — Spring types it 500 for that
     * reason. Mapped explicitly so the ServletRequestBindingException net above
     * (which is 4xx-only in intent) cannot quietly swallow it: this class is a
     * strict subclass, so ExceptionDepthComparator prefers this method, and the
     * stack trace survives.
     */
    @ExceptionHandler(MissingPathVariableException.class)
    public ResponseEntity<ApiError> handleMissingPathVariable(MissingPathVariableException ex) {
        return handleUnexpected(ex);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        // Full detail server-side only — never leak internals to the client
        log.error("Unhandled exception", ex);
        return ResponseEntity.internalServerError()
                .body(ApiError.of(500, "internal_error", "something went wrong"));
    }
}
