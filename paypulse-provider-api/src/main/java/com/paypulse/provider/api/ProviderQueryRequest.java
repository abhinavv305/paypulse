package com.paypulse.provider.api;

import java.util.UUID;

public record ProviderQueryRequest(
    UUID paymentId,
    UUID merchantId,
    String providerPaymentId,
    String idempotencyKey
) {}
