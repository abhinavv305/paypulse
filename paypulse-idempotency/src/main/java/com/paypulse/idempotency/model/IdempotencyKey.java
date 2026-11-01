package com.paypulse.idempotency.model;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "idempotency_keys", uniqueConstraints = {
    @UniqueConstraint(name = "uq_merchant_idempotency", columnNames = {"merchant_id", "idempotency_key"})
})
public class IdempotencyKey implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String key;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(nullable = false, length = 32)
    private String status; // IN_PROGRESS, COMPLETED, FAILED

    @Column(name = "response_code")
    private Integer responseCode;

    @Column(name = "response_headers", columnDefinition = "TEXT")
    private String responseHeaders;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "locked_at", nullable = false)
    private Instant lockedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Transient
    private boolean isNew = true;

    protected IdempotencyKey() {
        this.isNew = false;
    }

    public IdempotencyKey(UUID merchantId, String key, String requestHash, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId must not be null");
        this.key = Objects.requireNonNull(key, "key must not be null");
        this.requestHash = Objects.requireNonNull(requestHash, "requestHash must not be null");
        this.status = "IN_PROGRESS";
        this.createdAt = Instant.now();
        this.lockedAt = Instant.now();
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
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

    public boolean isInProgress() {
        return "IN_PROGRESS".equals(status);
    }

    public boolean isCompleted() {
        return "COMPLETED".equals(status);
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    public void markCompleted(UUID resourceId, int responseCode, String responseBody, String responseHeaders) {
        this.resourceId = resourceId;
        this.responseCode = responseCode;
        this.responseBody = responseBody;
        this.responseHeaders = responseHeaders;
        this.status = "COMPLETED";
    }

    public void markFailed(int responseCode, String responseBody) {
        this.responseCode = responseCode;
        this.responseBody = responseBody;
        this.status = "FAILED";
    }

    @Override
    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public String getKey() { return key; }
    public String getRequestHash() { return requestHash; }
    public UUID getResourceId() { return resourceId; }
    public String getStatus() { return status; }
    public Integer getResponseCode() { return responseCode; }
    public String getResponseHeaders() { return responseHeaders; }
    public String getResponseBody() { return responseBody; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLockedAt() { return lockedAt; }
    public Instant getExpiresAt() { return expiresAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IdempotencyKey that = (IdempotencyKey) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
