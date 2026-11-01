package com.paypulse.domain.model;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "refunds")
public class Refund implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "provider_refund_id")
    private String providerRefundId;

    @Column(name = "amount_minor_units", nullable = false)
    private long amountMinorUnits;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 32)
    private String status; // PENDING, SUCCEEDED, FAILED

    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Transient
    private boolean isNew = true;

    protected Refund() {
        this.isNew = false;
    }

    public Refund(UUID paymentId, long amountMinorUnits, String currency, String reason) {
        this.id = UUID.randomUUID();
        this.paymentId = paymentId;
        this.amountMinorUnits = amountMinorUnits;
        this.currency = currency;
        this.reason = reason;
        this.status = "PENDING";
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
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

    public void markSucceeded(String providerRefundId) {
        this.providerRefundId = providerRefundId;
        this.status = "SUCCEEDED";
        this.updatedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.reason = reason;
        this.status = "FAILED";
        this.updatedAt = Instant.now();
    }

    @Override
    public UUID getId() { return id; }
    public UUID getPaymentId() { return paymentId; }
    public String getProviderRefundId() { return providerRefundId; }
    public long getAmountMinorUnits() { return amountMinorUnits; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Refund refund = (Refund) o;
        return Objects.equals(id, refund.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
