package com.paypulse.domain.exception;

public class IllegalPaymentStateException extends RuntimeException {
    public IllegalPaymentStateException(String message) {
        super(message);
    }
}
