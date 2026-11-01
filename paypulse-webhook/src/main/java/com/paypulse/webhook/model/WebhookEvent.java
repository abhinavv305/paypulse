package com.paypulse.webhook.model;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "webhook_events", uniqueConstraints = {
    @UniqueConstraint(name = "uq_provider_event", columnNames = {"provider_id", "provider_event_id"})
})
public class WebhookEvent implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "provider_id", nullable = false, length = 32)
    private String providerId;

    @Column(name = "provider_event_id", nullable = false)
    private String providerEventId;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Column(name = "signature_header", columnDefinition = "TEXT")
    private String signatureHeader;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payload;

    @Column(nullable = false, length = 32)
    private String status; // PENDING, PROCESSED, SUPERSEDED, FAILED

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "processed_at")
    private Instant processedAt;

    @Transient
    private boolean isNew = true;

    protected WebhookEvent() {
        this.isNew = false;
    }

    public WebhookEvent(
            String providerId,
            String providerEventId,
            String eventType,
            String signatureHeader,
            String payload) {
        this.id = UUID.randomUUID();
        this.providerId = Objects.requireNonNull(providerId);
        this.providerEventId = Objects.requireNonNull(providerEventId);
        this.eventType = Objects.requireNonNull(eventType);
        this.signatureHeader = signatureHeader;
        this.payload = Objects.requireNonNull(payload);
        this.status = "PENDING";
        this.retryCount = 0;
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

    public void markProcessed() {
        this.status = "PROCESSED";
        this.processedAt = Instant.now();
    }

    public void markSuperseded(String reason) {
        this.status = "SUPERSEDED";
        this.errorMessage = reason;
        this.processedAt = Instant.now();
    }

    public void recordFailure(String error) {
        this.retryCount++;
        this.errorMessage = error;
        if (this.retryCount >= 5) {
            this.status = "FAILED";
        }
    }

    @Override
    public UUID getId() { return id; }
    public String getProviderId() { return providerId; }
    public String getProviderEventId() { return providerEventId; }
    public String getEventType() { return eventType; }
    public String getSignatureHeader() { return signatureHeader; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public int getRetryCount() { return retryCount; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getProcessedAt() { return processedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WebhookEvent that = (WebhookEvent) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
