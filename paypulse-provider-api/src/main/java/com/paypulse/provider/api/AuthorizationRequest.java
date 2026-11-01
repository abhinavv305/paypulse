package com.paypulse.provider.api;

import com.paypulse.domain.model.CaptureMethod;
import com.paypulse.domain.model.Money;

import java.util.Map;
import java.util.UUID;

public record AuthorizationRequest(
    UUID paymentId,
    UUID merchantId,
    String idempotencyKey,
    Money money,
    String paymentMethodToken,
    CaptureMethod captureMethod,
    String customerEmail,
    String description,
    Map<String, String> metadata
) {}
