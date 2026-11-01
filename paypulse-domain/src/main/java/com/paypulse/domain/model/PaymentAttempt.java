package com.paypulse.domain.model;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "payment_attempts")
public class PaymentAttempt implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "provider_id", nullable = false, length = 32)
    private String providerId;

    @Column(name = "provider_transaction_id")
    private String providerTransactionId;

    @Column(name = "request_payload", columnDefinition = "TEXT")
    private String requestPayload;

    @Column(name = "response_payload", columnDefinition = "TEXT")
    private String responsePayload;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Transient
    private boolean isNew = true;

    protected PaymentAttempt() {
        this.isNew = false;
    }

    public PaymentAttempt(
            UUID paymentId,
            int attemptNumber,
            String providerId,
            String providerTransactionId,
            String requestPayload,
            String responsePayload,
            Integer httpStatus,
            Integer latencyMs,
            String status) {
        this.id = UUID.randomUUID();
        this.paymentId = paymentId;
        this.attemptNumber = attemptNumber;
        this.providerId = providerId;
        this.providerTransactionId = providerTransactionId;
        this.requestPayload = requestPayload;
        this.responsePayload = responsePayload;
        this.httpStatus = httpStatus;
        this.latencyMs = latencyMs;
        this.status = status;
        this.createdAt = Instant.now();
        this.isNew = true;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public UUID getId() { return id; }
    public UUID getPaymentId() { return paymentId; }
    public int getAttemptNumber() { return attemptNumber; }
    public String getProviderId() { return providerId; }
    public String getProviderTransactionId() { return providerTransactionId; }
    public String getRequestPayload() { return requestPayload; }
    public String getResponsePayload() { return responsePayload; }
    public Integer getHttpStatus() { return httpStatus; }
    public Integer getLatencyMs() { return latencyMs; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PaymentAttempt that = (PaymentAttempt) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
