package com.paypulse.provider.api;

import com.paypulse.domain.model.Money;

import java.util.UUID;

public record RefundRequest(
    UUID paymentId,
    UUID merchantId,
    String providerPaymentId,
    Money money,
    UUID refundId,
    String reason,
    String idempotencyKey
) {}
