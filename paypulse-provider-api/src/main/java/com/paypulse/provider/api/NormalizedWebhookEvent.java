package com.paypulse.provider.api;

import com.paypulse.domain.model.Money;
import com.paypulse.domain.model.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

public record NormalizedWebhookEvent(
    ProviderId providerId,
    String providerEventId,
    String eventType,
    UUID paymentId,
    String providerPaymentId,
    PaymentStatus targetStatus,
    Money amount,
    Instant occurredAt,
    String rawPayload
) {}
