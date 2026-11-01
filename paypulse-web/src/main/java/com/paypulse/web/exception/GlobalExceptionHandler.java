package com.paypulse.web.exception;

import com.paypulse.domain.exception.IdempotencyConflictException;
import com.paypulse.domain.exception.IllegalPaymentStateException;
import com.paypulse.domain.exception.InvalidPaymentAmountException;
import com.paypulse.domain.exception.PaymentNotFoundException;
import com.paypulse.domain.exception.PayloadHashMismatchException;
import com.paypulse.web.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException ex) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", String.valueOf(ex.getRetryAfterSeconds()));
        return new ResponseEntity<>(
            new ErrorResponse("CONCURRENT_REQUEST_IN_PROGRESS", ex.getMessage(), ex.getRetryAfterSeconds()),
            headers,
            HttpStatus.CONFLICT // 409 Conflict
        );
    }

    @ExceptionHandler(PayloadHashMismatchException.class)
    public ResponseEntity<ErrorResponse> handlePayloadHashMismatch(PayloadHashMismatchException ex) {
        return new ResponseEntity<>(
            new ErrorResponse("IDEMPOTENCY_PAYLOAD_MISMATCH", ex.getMessage()),
            HttpStatus.UNPROCESSABLE_ENTITY // 422 Unprocessable Entity
        );
    }

    @ExceptionHandler(IllegalPaymentStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalPaymentStateException ex) {
        return new ResponseEntity<>(
            new ErrorResponse("ILLEGAL_PAYMENT_STATE_TRANSITION", ex.getMessage()),
            HttpStatus.BAD_REQUEST // 400 Bad Request
        );
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(PaymentNotFoundException ex) {
        return new ResponseEntity<>(
            new ErrorResponse("PAYMENT_NOT_FOUND", ex.getMessage()),
            HttpStatus.NOT_FOUND // 404 Not Found
        );
    }

    @ExceptionHandler(InvalidPaymentAmountException.class)
    public ResponseEntity<ErrorResponse> handleInvalidAmount(InvalidPaymentAmountException ex) {
        return new ResponseEntity<>(
            new ErrorResponse("INVALID_PAYMENT_AMOUNT", ex.getMessage()),
            HttpStatus.BAD_REQUEST
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(err -> err.getField() + ": " + err.getDefaultMessage())
            .findFirst()
            .orElse("Validation failed");
        return new ResponseEntity<>(
            new ErrorResponse("VALIDATION_ERROR", message),
            HttpStatus.BAD_REQUEST
        );
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        return new ResponseEntity<>(
            new ErrorResponse("MISSING_REQUIRED_HEADER", "Missing header: " + ex.getHeaderName()),
            HttpStatus.BAD_REQUEST
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        log.error("Unhandled server exception: {}", ex.getMessage(), ex);
        return new ResponseEntity<>(
            new ErrorResponse("INTERNAL_SERVER_ERROR", "An unexpected error occurred"),
            HttpStatus.INTERNAL_SERVER_ERROR
        );
    }
}
