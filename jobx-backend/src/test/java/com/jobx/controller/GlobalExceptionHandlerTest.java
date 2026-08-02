package com.jobx.controller;

import com.jobx.dto.ApiError;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;

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
}
