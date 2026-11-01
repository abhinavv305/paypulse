package com.paypulse.web.dto;

import com.paypulse.domain.model.Payment;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
    UUID id,
    UUID merchantId,
    long amount,
    String currency,
    String status,
    String provider,
    String providerPaymentId,
    String captureMethod,
    String description,
    String customerEmail,
    String errorCode,
    String errorMessage,
    Instant createdAt,
    Instant updatedAt
) {
    public static PaymentResponse fromDomain(Payment payment) {
        return new PaymentResponse(
            payment.getId(),
            payment.getMerchantId(),
            payment.getAmountMinorUnits(),
            payment.getCurrency(),
            payment.getStatus().name(),
            payment.getProviderId(),
            payment.getProviderPaymentId(),
            payment.getCaptureMethod().name(),
            payment.getDescription(),
            payment.getCustomerEmail(),
            payment.getErrorCode(),
            payment.getErrorMessage(),
            payment.getCreatedAt(),
            payment.getUpdatedAt()
        );
    }
}
