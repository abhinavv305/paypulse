package com.paypulse.webhook.service;

import com.paypulse.provider.api.PaymentProviderGateway;
import com.paypulse.provider.api.ProviderId;
import com.paypulse.provider.api.ProviderRoutingService;
import com.paypulse.provider.api.WebhookVerificationResult;
import com.paypulse.webhook.model.WebhookEvent;
import com.paypulse.webhook.repository.WebhookEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

@Service
public class WebhookIngestionService {

    private static final Logger log = LoggerFactory.getLogger(WebhookIngestionService.class);

    private final WebhookEventRepository repository;
    private final ProviderRoutingService providerRoutingService;
    private final TransactionTemplate requiresNewTemplate;

    public WebhookIngestionService(
            WebhookEventRepository repository,
            ProviderRoutingService providerRoutingService,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.providerRoutingService = providerRoutingService;
        this.requiresNewTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record IngestionResult(Status status, String message, String eventId) {
        public enum Status {
            ACCEPTED,
            DUPLICATE_ACKNOWLEDGED,
            INVALID_SIGNATURE,
            PROVIDER_NOT_FOUND
        }
    }

    public IngestionResult ingestWebhook(
            ProviderId providerId,
            Map<String, String> headers,
            byte[] rawPayload) {

        Optional<PaymentProviderGateway> gatewayOpt = providerRoutingService.findGateway(providerId);
        if (gatewayOpt.isEmpty()) {
            return new IngestionResult(IngestionResult.Status.PROVIDER_NOT_FOUND, "Unknown provider: " + providerId, null);
        }

        PaymentProviderGateway gateway = gatewayOpt.get();
        WebhookVerificationResult verification = gateway.verifyWebhookSignature(headers, rawPayload);

        if (verification instanceof WebhookVerificationResult.Invalid invalid) {
            log.warn("Rejected webhook for provider {}: {}", providerId, invalid.reason());
            return new IngestionResult(IngestionResult.Status.INVALID_SIGNATURE, invalid.reason(), null);
        }

        WebhookVerificationResult.Valid valid = (WebhookVerificationResult.Valid) verification;
        String providerEventId = valid.eventId();
        String eventType = valid.eventType();

        // Fast Deduplication Check
        Optional<WebhookEvent> existing = repository.findByProviderIdAndProviderEventId(providerId.name(), providerEventId);
        if (existing.isPresent()) {
            log.info("Duplicate webhook event acknowledged immediately: {}/{}", providerId, providerEventId);
            return new IngestionResult(IngestionResult.Status.DUPLICATE_ACKNOWLEDGED, "Event previously ingested", providerEventId);
        }

        try {
            String payloadStr = new String(rawPayload, StandardCharsets.UTF_8);
            WebhookEvent webhookEvent = new WebhookEvent(
                providerId.name(),
                providerEventId,
                eventType,
                headers.toString(),
                payloadStr
            );
            requiresNewTemplate.executeWithoutResult(status -> {
                repository.saveAndFlush(webhookEvent);
            });
            log.info("Successfully ingested webhook event: {}/{}", providerId, providerEventId);
            return new IngestionResult(IngestionResult.Status.ACCEPTED, "Event queued for asynchronous processing", providerEventId);
        } catch (Exception ex) {
            // Concurrent duplicate insertion race condition caught by UNIQUE constraint
            log.info("Concurrent duplicate webhook caught: {}/{}", providerId, providerEventId);
            return new IngestionResult(IngestionResult.Status.DUPLICATE_ACKNOWLEDGED, "Concurrent duplicate acknowledged", providerEventId);
        }
    }
}
