package com.paypulse.reconciliation.model;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_audit_logs")
public class ReconciliationAuditLog implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "initial_status", nullable = false, length = 32)
    private String initialStatus;

    @Column(name = "resolved_status", nullable = false, length = 32)
    private String resolvedStatus;

    @Column(name = "provider_status", nullable = false, length = 64)
    private String providerStatus;

    @Column(name = "action_taken", nullable = false, length = 128)
    private String actionTaken;

    @Column(name = "discrepancy_reason", columnDefinition = "TEXT")
    private String discrepancyReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Transient
    private boolean isNew = true;

    protected ReconciliationAuditLog() {
        this.isNew = false;
    }

    public ReconciliationAuditLog(
            UUID paymentId,
            String initialStatus,
            String resolvedStatus,
            String providerStatus,
            String actionTaken,
            String discrepancyReason) {
        this.id = UUID.randomUUID();
        this.paymentId = Objects.requireNonNull(paymentId);
        this.initialStatus = Objects.requireNonNull(initialStatus);
        this.resolvedStatus = Objects.requireNonNull(resolvedStatus);
        this.providerStatus = Objects.requireNonNull(providerStatus);
        this.actionTaken = Objects.requireNonNull(actionTaken);
        this.discrepancyReason = discrepancyReason;
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
    public String getInitialStatus() { return initialStatus; }
    public String getResolvedStatus() { return resolvedStatus; }
    public String getProviderStatus() { return providerStatus; }
    public String getActionTaken() { return actionTaken; }
    public String getDiscrepancyReason() { return discrepancyReason; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReconciliationAuditLog that = (ReconciliationAuditLog) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
