package com.jobx.controller;

import com.jobx.dto.ApiError;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void responseStatusExceptionKeepsStatusAndReason() {
        ResponseEntity<ApiError> response = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.CONFLICT, "email already registered"));

        assertEquals(409, response.getStatusCode().value());
        assertEquals(409, response.getBody().status());
        assertEquals("conflict", response.getBody().code());
        assertEquals("email already registered", response.getBody().detail());
    }

    @Test
    void validationErrorsMapPerField() {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
        binding.addError(new FieldError("request", "boardToken", "boardToken is required"));

        ResponseEntity<ApiError> response = handler.handleValidation(
                new MethodArgumentNotValidException(null, binding));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("validation_failed", response.getBody().code());
        assertEquals("boardToken is required", response.getBody().fieldErrors().get("boardToken"));
    }

    @Test
    void unreadableBodyIs400WithoutInternals() {
        ResponseEntity<ApiError> response = handler.handleUnreadable(
                new HttpMessageNotReadableException("Cannot deserialize value of type AtsPlatform",
                        new MockHttpInputMessage(new ByteArrayInputStream(new byte[0]))));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("malformed_request", response.getBody().code());
        // Detail must not echo parser internals
        assertFalse(response.getBody().detail().contains("deserialize"));
    }

    @Test
    void dataIntegrityViolationIs409() {
        ResponseEntity<ApiError> response = handler.handleConflict(
                new DataIntegrityViolationException("duplicate key"));

        assertEquals(409, response.getStatusCode().value());
        assertEquals("conflict", response.getBody().code());
    }

    @Test
    void unexpectedExceptionIs500WithGenericDetail() {
        ResponseEntity<ApiError> response = handler.handleUnexpected(
                new RuntimeException("NullPointerException at line 42"));

        assertEquals(500, response.getStatusCode().value());
        assertEquals("internal_error", response.getBody().code());
        assertFalse(response.getBody().detail().contains("NullPointerException"));
    }

    // --- BUG_REPORT #7: exceptions Spring throws before/instead of a controller.
    // These all answered 500 internal_error before handleFrameworkRejection existed.

    @Test
    void unknownPathIs404AndDoesNotEchoThePath() {
        ResponseEntity<ApiError> response = handler.handleFrameworkRejection(
                new NoResourceFoundException(HttpMethod.GET, "/watchlist/nope"),
                request("GET", "/watchlist/nope"));

        assertEquals(404, response.getStatusCode().value());
        assertEquals(404, response.getBody().status());
        assertEquals("not_found", response.getBody().code());
        // The exception's own message is "No static resource /watchlist/nope." —
        // it reflects the caller's path back and names an internal dispatch detail.
        assertFalse(response.getBody().detail().contains("/watchlist/nope"));
        assertFalse(response.getBody().detail().contains("static resource"));
    }

    /** Pins the claim that the contract does not depend on spring.web.resources.add-mappings. */
    @Test
    void noHandlerFoundIsAlso404() {
        ResponseEntity<ApiError> response = handler.handleFrameworkRejection(
                new NoHandlerFoundException("GET", "/nope", HttpHeaders.EMPTY),
                request("GET", "/nope"));

        assertEquals(404, response.getStatusCode().value());
        assertEquals("not_found", response.getBody().code());
        // getHeaders() on this one delegates to ErrorResponse's default (empty) —
        // the request headers it carries must never be copied onto the response.
        assertTrue(response.getHeaders().isEmpty());
    }

    @Test
    void wrongMethodIs405WithAllowHeader() {
        ResponseEntity<ApiError> response = handler.handleFrameworkRejection(
                new HttpRequestMethodNotSupportedException("GET", List.of("POST")),
                request("GET", "/auth/login"));

        assertEquals(405, response.getStatusCode().value());
        assertEquals("method_not_allowed", response.getBody().code());
        assertEquals("POST", response.getHeaders().getFirst(HttpHeaders.ALLOW));
    }

    /** Guards the HttpHeaders.EMPTY branch of the exception's getHeaders(). */
    @Test
    void methodNotSupportedWithNoSupportedMethodsHasNoAllowHeader() {
        ResponseEntity<ApiError> response = handler.handleFrameworkRejection(
                new HttpRequestMethodNotSupportedException("TRACE"),
                request("TRACE", "/auth/login"));

        assertEquals(405, response.getStatusCode().value());
        assertNull(response.getHeaders().getFirst(HttpHeaders.ALLOW));
    }

    @Test
    void missingParameterNamesTheParameter() {
        ResponseEntity<ApiError> response = handler.handleFrameworkRejection(
                new MissingServletRequestParameterException("q", "String"),
                request("GET", "/companies/search"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("missing_parameter", response.getBody().code());
        assertTrue(response.getBody().detail().contains("'q'"));
    }

    @Test
    void unsupportedMediaTypeIs415WithAcceptHeader() {
        ResponseEntity<ApiError> response = handler.handleFrameworkRejection(
                new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN,
                        List.of(MediaType.APPLICATION_JSON)),
                request("POST", "/auth/login"));

        assertEquals(415, response.getStatusCode().value());
        assertEquals("unsupported_media_type", response.getBody().code());
        assertEquals(List.of(MediaType.APPLICATION_JSON), response.getHeaders().getAccept());
    }

    @Test
    void notAcceptableIs406() {
        ResponseEntity<ApiError> response = handler.handleFrameworkRejection(
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)),
                request("GET", "/matches"));

        assertEquals(406, response.getStatusCode().value());
        assertEquals("not_acceptable", response.getBody().code());
    }

    /** No framework rejection may leak a class name or a Spring internal. */
    @Test
    void frameworkRejectionDetailsNeverLeakInternals() {
        List<ErrorResponse> rejections = List.of(
                new NoResourceFoundException(HttpMethod.GET, "/watchlist/nope"),
                new NoHandlerFoundException("GET", "/nope", HttpHeaders.EMPTY),
                new HttpRequestMethodNotSupportedException("GET", List.of("POST")),
                new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN,
                        List.of(MediaType.APPLICATION_JSON)),
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)),
                new MissingServletRequestParameterException("q", "String"));

        for (ErrorResponse rejection : rejections) {
            String detail = handler.handleFrameworkRejection(rejection, request("GET", "/x"))
                    .getBody().detail();
            assertNotNull(detail, rejection.getClass().getSimpleName() + " has no detail");
            assertFalse(detail.contains("Exception"), detail);
            assertFalse(detail.contains("org.springframework"), detail);
        }
    }

    /**
     * A path variable the mapping cannot supply means our routing is wrong, not the
     * caller's request — Spring types it 500 and it must keep the 500 path (and its
     * stack trace) rather than falling into the 4xx net via ServletRequestBindingException.
     */
    @Test
    void missingPathVariableStaysA500() throws Exception {
        MethodParameter parameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("routeWithId", UUID.class), 0);

        ResponseEntity<ApiError> response = handler.handleMissingPathVariable(
                new MissingPathVariableException("id", parameter));

        assertEquals(500, response.getStatusCode().value());
        assertEquals("internal_error", response.getBody().code());
    }

    /** Signature-only fixture for the MethodParameter above; never called. */
    @SuppressWarnings("unused")
    private void routeWithId(UUID id) {
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }
}
