package com.paypulse.domain.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "merchants")
public class Merchant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "api_key_hash", nullable = false, length = 64)
    private String apiKeyHash;

    @Column(name = "webhook_secret", nullable = false)
    private String webhookSecret;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Merchant() {}

    public Merchant(UUID id, String name, String apiKeyHash, String webhookSecret) {
        this.id = id;
        this.name = name;
        this.apiKeyHash = apiKeyHash;
        this.webhookSecret = webhookSecret;
        this.active = true;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void updateTimestamp() {
        this.updatedAt = Instant.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Merchant merchant = (Merchant) o;
        return Objects.equals(id, merchant.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
