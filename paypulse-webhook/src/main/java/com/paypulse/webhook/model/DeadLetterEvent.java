package com.paypulse.webhook.model;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "dead_letter_events")
public class DeadLetterEvent implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payload;

    @Column(columnDefinition = "TEXT")
    private String headers;

    @Column(name = "error_class", nullable = false)
    private String errorClass;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "stack_trace", columnDefinition = "TEXT")
    private String stackTrace;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(nullable = false)
    private boolean replayed = false;

    @Column(name = "replayed_at")
    private Instant replayedAt;

    @Column(name = "replayed_by", length = 64)
    private String replayedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Transient
    private boolean isNew = true;

    protected DeadLetterEvent() {
        this.isNew = false;
    }

    public DeadLetterEvent(
            String sourceType,
            UUID sourceId,
            String payload,
            String headers,
            String errorClass,
            String errorMessage,
            String stackTrace,
            int retryCount) {
        this.id = UUID.randomUUID();
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.payload = payload;
        this.headers = headers;
        this.errorClass = errorClass;
        this.errorMessage = errorMessage;
        this.stackTrace = stackTrace;
        this.retryCount = retryCount;
        this.replayed = false;
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

    public void markReplayed(String adminUser) {
        this.replayed = true;
        this.replayedAt = Instant.now();
        this.replayedBy = adminUser;
    }

    @Override
    public UUID getId() { return id; }
    public String getSourceType() { return sourceType; }
    public UUID getSourceId() { return sourceId; }
    public String getPayload() { return payload; }
    public String getHeaders() { return headers; }
    public String getErrorClass() { return errorClass; }
    public String getErrorMessage() { return errorMessage; }
    public String getStackTrace() { return stackTrace; }
    public int getRetryCount() { return retryCount; }
    public boolean isReplayed() { return replayed; }
    public Instant getReplayedAt() { return replayedAt; }
    public String getReplayedBy() { return replayedBy; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DeadLetterEvent that = (DeadLetterEvent) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
