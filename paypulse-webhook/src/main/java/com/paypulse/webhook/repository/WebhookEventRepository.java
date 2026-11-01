package com.paypulse.webhook.repository;

import com.paypulse.webhook.model.WebhookEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {

    Optional<WebhookEvent> findByProviderIdAndProviderEventId(String providerId, String providerEventId);

    @Query("SELECT w FROM WebhookEvent w WHERE w.status = :status ORDER BY w.createdAt ASC")
    List<WebhookEvent> findPendingEvents(@Param("status") String status, Pageable pageable);
}
