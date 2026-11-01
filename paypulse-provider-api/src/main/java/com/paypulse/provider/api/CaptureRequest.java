package com.paypulse.provider.api;

import com.paypulse.domain.model.Money;

import java.util.UUID;

public record CaptureRequest(
    UUID paymentId,
    UUID merchantId,
    String providerPaymentId,
    Money money,
    String idempotencyKey
) {}
