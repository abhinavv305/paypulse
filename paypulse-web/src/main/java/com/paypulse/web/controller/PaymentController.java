package com.paypulse.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypulse.idempotency.service.IdempotencyService;
import com.paypulse.web.dto.CapturePaymentRequest;
import com.paypulse.web.dto.CreatePaymentRequest;
import com.paypulse.web.dto.PaymentResponse;
import com.paypulse.web.dto.RefundPaymentRequest;
import com.paypulse.web.service.PaymentOrchestratorService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/v1/payments")
public class PaymentController {

    private final PaymentOrchestratorService paymentOrchestrator;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public PaymentController(
            PaymentOrchestratorService paymentOrchestrator,
            IdempotencyService idempotencyService,
            ObjectMapper objectMapper) {
        this.paymentOrchestrator = paymentOrchestrator;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Merchant-Id") String merchantIdHeader,
            @Valid @RequestBody CreatePaymentRequest request) {

        UUID merchantId = UUID.fromString(merchantIdHeader);
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            payloadJson = request.toString();
        }

        String requestHash = IdempotencyService.computeHash("POST", "/v1/payments", payloadJson);

        var result = idempotencyService.executeIdempotent(
            merchantId,
            idempotencyKey,
            requestHash,
            Duration.ofSeconds(10),
            PaymentResponse.class,
            () -> {
                PaymentResponse response = paymentOrchestrator.createAndProcessPayment(
                    merchantId,
                    idempotencyKey,
                    request
                );
                return new IdempotencyService.ExecutionOutput<>(
                    response.id(),
                    HttpStatus.CREATED.value(),
                    response
                );
            }
        );

        HttpHeaders headers = new HttpHeaders();
        if (result.isReplay()) {
            headers.add("Idempotent-Replay", "true");
            return new ResponseEntity<>(result.responseBody(), headers, HttpStatus.OK);
        }

        return new ResponseEntity<>(result.responseBody(), headers, HttpStatus.valueOf(result.statusCode()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> getPayment(
            @PathVariable("id") UUID id,
            @RequestHeader("X-Merchant-Id") String merchantIdHeader) {
        UUID merchantId = UUID.fromString(merchantIdHeader);
        return ResponseEntity.ok(paymentOrchestrator.getPayment(id, merchantId));
    }

    @PostMapping("/{id}/capture")
    public ResponseEntity<PaymentResponse> capturePayment(
            @PathVariable("id") UUID id,
            @RequestHeader("X-Merchant-Id") String merchantIdHeader,
            @RequestBody(required = false) CapturePaymentRequest request) {
        UUID merchantId = UUID.fromString(merchantIdHeader);
        return ResponseEntity.ok(paymentOrchestrator.capturePayment(id, merchantId, request));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<PaymentResponse> cancelPayment(
            @PathVariable("id") UUID id,
            @RequestHeader("X-Merchant-Id") String merchantIdHeader) {
        UUID merchantId = UUID.fromString(merchantIdHeader);
        return ResponseEntity.ok(paymentOrchestrator.cancelPayment(id, merchantId));
    }

    @PostMapping("/{id}/refund")
    public ResponseEntity<PaymentResponse> refundPayment(
            @PathVariable("id") UUID id,
            @RequestHeader("X-Merchant-Id") String merchantIdHeader,
            @Valid @RequestBody(required = false) RefundPaymentRequest request) {
        UUID merchantId = UUID.fromString(merchantIdHeader);
        return ResponseEntity.ok(paymentOrchestrator.refundPayment(id, merchantId, request != null ? request : new RefundPaymentRequest(null, "Customer request")));
    }
}
