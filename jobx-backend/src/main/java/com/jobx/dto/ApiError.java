package com.jobx.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * The single error shape every non-2xx response uses (V1_IMPROVEMENTS.md P0:
 * "the frontend needs predictable errors"). Produced by GlobalExceptionHandler;
 * the 401 entry point and 429 rate limiter emit the same shape by hand.
 *
 * code is a stable machine-readable slug the Angular app can switch on;
 * detail is a human-readable message safe to show the user (never a stack trace).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        int status,
        String code,
        String detail,
        Map<String, String> fieldErrors
) {
    public static ApiError of(int status, String code, String detail) {
        return new ApiError(status, code, detail, null);
    }
}
