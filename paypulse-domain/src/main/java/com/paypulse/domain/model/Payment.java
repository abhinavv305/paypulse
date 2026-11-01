package com.paypulse.domain.model;

import com.paypulse.domain.exception.IllegalPaymentStateException;
import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "idempotency_key_id")
    private UUID idempotencyKeyId;

    @Column(name = "provider_id", nullable = false, length = 32)
    private String providerId;

    @Column(name = "provider_payment_id")
    private String providerPaymentId;

    @Embedded
    private Money money;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentStatus status;

    @Column(name = "state_sequence_number", nullable = false)
    private int stateSequenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "capture_method", nullable = false, length = 16)
    private CaptureMethod captureMethod;

    @Column(name = "client_token")
    private String clientToken;

    @Column(name = "customer_email")
    private String customerEmail;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Transient
    private boolean isNew = true;

    protected Payment() {
        this.isNew = false;
    }

    public Payment(
            UUID merchantId,
            UUID idempotencyKeyId,
            String providerId,
            Money money,
            CaptureMethod captureMethod,
            String customerEmail,
            String description) {
        this.id = UUID.randomUUID();
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId must not be null");
        this.idempotencyKeyId = idempotencyKeyId;
        this.providerId = Objects.requireNonNull(providerId, "providerId must not be null");
        this.money = Objects.requireNonNull(money, "money must not be null");
        this.captureMethod = captureMethod != null ? captureMethod : CaptureMethod.AUTOMATIC;
        this.customerEmail = customerEmail;
        this.description = description;
        this.status = PaymentStatus.CREATED;
        this.stateSequenceNumber = PaymentStatus.CREATED.getSequenceNumber();
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

    public synchronized void transitionTo(PaymentStatus newStatus) {
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        if (this.status == newStatus) {
            return;
        }
        if (!this.status.canTransitionTo(newStatus)) {
            throw new IllegalPaymentStateException(
                String.format("Illegal state transition from %s to %s for payment %s", this.status, newStatus, this.id)
            );
        }
        this.status = newStatus;
        this.stateSequenceNumber = newStatus.getSequenceNumber();
        this.updatedAt = Instant.now();
    }

    public void markAuthorizing() {
        transitionTo(PaymentStatus.AUTHORIZING);
    }

    public void markAuthorized(String providerPaymentId) {
        this.providerPaymentId = providerPaymentId;
        transitionTo(PaymentStatus.AUTHORIZED);
    }

    public void markCapturing() {
        transitionTo(PaymentStatus.CAPTURING);
    }

    public void markSucceeded(String providerPaymentId) {
        if (providerPaymentId != null) {
            this.providerPaymentId = providerPaymentId;
        }
        transitionTo(PaymentStatus.SUCCEEDED);
    }

    public void markDeclined(String errorCode, String errorMessage) {
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        transitionTo(PaymentStatus.DECLINED);
    }

    public void markCancelled() {
        transitionTo(PaymentStatus.CANCELLED);
    }

    public void markRefunding() {
        transitionTo(PaymentStatus.REFUNDING);
    }

    public void markRefunded() {
        transitionTo(PaymentStatus.REFUNDED);
    }

    public void markPartiallyRefunded() {
        transitionTo(PaymentStatus.PARTIALLY_REFUNDED);
    }

    public void markRequiresReconciliation(String reason) {
        this.errorMessage = reason;
        transitionTo(PaymentStatus.REQUIRES_RECONCILIATION);
    }

    public void markFailed(String errorCode, String errorMessage) {
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        transitionTo(PaymentStatus.FAILED);
    }

    @Override
    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getIdempotencyKeyId() { return idempotencyKeyId; }
    public String getProviderId() { return providerId; }
    public String getProviderPaymentId() { return providerPaymentId; }
    public Money getMoney() { return money; }
    public long getAmountMinorUnits() { return money != null ? money.getAmountMinorUnits() : 0L; }
    public String getCurrency() { return money != null ? money.getCurrency() : null; }
    public PaymentStatus getStatus() { return status; }
    public int getStateSequenceNumber() { return stateSequenceNumber; }
    public CaptureMethod getCaptureMethod() { return captureMethod; }
    public String getClientToken() { return clientToken; }
    public String getCustomerEmail() { return customerEmail; }
    public String getDescription() { return description; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setClientToken(String clientToken) { this.clientToken = clientToken; }
    public void setProviderPaymentId(String providerPaymentId) { this.providerPaymentId = providerPaymentId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Payment payment = (Payment) o;
        return Objects.equals(id, payment.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
