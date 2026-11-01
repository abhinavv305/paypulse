package com.paypulse.provider.api;

public sealed interface WebhookVerificationResult
    permits WebhookVerificationResult.Valid,
            WebhookVerificationResult.Invalid {

    record Valid(String eventId, String eventType) implements WebhookVerificationResult {}

    record Invalid(String reason) implements WebhookVerificationResult {}
}
