package com.paypulse.web.dto;

import java.time.Instant;

public record ErrorResponse(
    String errorCode,
    String message,
    Integer retryAfterSeconds,
    Instant timestamp
) {
    public ErrorResponse(String errorCode, String message) {
        this(errorCode, message, null, Instant.now());
    }

    public ErrorResponse(String errorCode, String message, Integer retryAfterSeconds) {
        this(errorCode, message, retryAfterSeconds, Instant.now());
    }
}
