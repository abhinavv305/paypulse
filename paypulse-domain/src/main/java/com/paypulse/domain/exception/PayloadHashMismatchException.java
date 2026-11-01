package com.paypulse.domain.exception;

public class PayloadHashMismatchException extends RuntimeException {
    private final String idempotencyKey;

    public PayloadHashMismatchException(String idempotencyKey, String message) {
        super(message);
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
