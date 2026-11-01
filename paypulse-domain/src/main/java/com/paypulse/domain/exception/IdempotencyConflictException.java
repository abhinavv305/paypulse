package com.paypulse.domain.exception;

public class IdempotencyConflictException extends RuntimeException {
    private final String idempotencyKey;
    private final int retryAfterSeconds;

    public IdempotencyConflictException(String idempotencyKey, String message, int retryAfterSeconds) {
        super(message);
        this.idempotencyKey = idempotencyKey;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
