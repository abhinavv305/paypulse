package com.paypulse.domain.event;

import com.paypulse.domain.model.Money;
import com.paypulse.domain.model.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

public sealed interface PaymentDomainEvent {

    UUID eventId();
    UUID paymentId();
    UUID merchantId();
    Instant occurredAt();
    String eventType();

    record PaymentCreated(
        UUID eventId,
        UUID paymentId,
        UUID merchantId,
        Money money,
        String providerId,
        Instant occurredAt
    ) implements PaymentDomainEvent {
        @Override public String eventType() { return "PaymentCreated"; }
    }

    record PaymentAuthorizing(
        UUID eventId,
        UUID paymentId,
        UUID merchantId,
        String providerId,
        Instant occurredAt
    ) implements PaymentDomainEvent {
        @Override public String eventType() { return "PaymentAuthorizing"; }
    }

    record PaymentAuthorized(
        UUID eventId,
        UUID paymentId,
        UUID merchantId,
        String providerPaymentId,
        Money authorizedAmount,
        Instant occurredAt
    ) implements PaymentDomainEvent {
        @Override public String eventType() { return "PaymentAuthorized"; }
    }

    record PaymentSucceeded(
        UUID eventId,
        UUID paymentId,
        UUID merchantId,
        String providerPaymentId,
        Money capturedAmount,
        Instant occurredAt
    ) implements PaymentDomainEvent {
        @Override public String eventType() { return "PaymentSucceeded"; }
    }

    record PaymentDeclined(
        UUID eventId,
        UUID paymentId,
        UUID merchantId,
        String errorCode,
        String errorMessage,
        Instant occurredAt
    ) implements PaymentDomainEvent {
        @Override public String eventType() { return "PaymentDeclined"; }
    }

    record PaymentCancelled(
        UUID eventId,
        UUID paymentId,
        UUID merchantId,
        Instant occurredAt
    ) implements PaymentDomainEvent {
        @Override public String eventType() { return "PaymentCancelled"; }
    }

    record PaymentRefunded(
        UUID eventId,
        UUID paymentId,
        UUID merchantId,
        Money refundedAmount,
        String providerRefundId,
        Instant occurredAt
    ) implements PaymentDomainEvent {
        @Override public String eventType() { return "PaymentRefunded"; }
    }

    record PaymentFailed(
        UUID eventId,
        UUID paymentId,
        UUID merchantId,
        String errorCode,
        String errorMessage,
        Instant occurredAt
    ) implements PaymentDomainEvent {
        @Override public String eventType() { return "PaymentFailed"; }
    }

    record PaymentReconciled(
        UUID eventId,
        UUID paymentId,
        UUID merchantId,
        PaymentStatus initialStatus,
        PaymentStatus resolvedStatus,
        String providerStatus,
        String actionTaken,
        Instant occurredAt
    ) implements PaymentDomainEvent {
        @Override public String eventType() { return "PaymentReconciled"; }
    }
}
