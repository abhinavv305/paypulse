package com.paypulse.webhook.service;

import com.paypulse.domain.event.PaymentDomainEvent;
import com.paypulse.domain.model.Payment;
import com.paypulse.domain.model.PaymentStatus;
import com.paypulse.domain.repository.PaymentRepository;
import com.paypulse.outbox.publisher.TransactionalOutboxPublisher;
import com.paypulse.provider.api.NormalizedWebhookEvent;
import com.paypulse.provider.api.PaymentProviderGateway;
import com.paypulse.provider.api.ProviderId;
import com.paypulse.provider.api.ProviderRoutingService;
import com.paypulse.webhook.model.DeadLetterEvent;
import com.paypulse.webhook.model.WebhookEvent;
import com.paypulse.webhook.repository.DeadLetterEventRepository;
import com.paypulse.webhook.repository.WebhookEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class WebhookProcessingService {

    private static final Logger log = LoggerFactory.getLogger(WebhookProcessingService.class);

    private final WebhookEventRepository webhookRepository;
    private final DeadLetterEventRepository dlqRepository;
    private final PaymentRepository paymentRepository;
    private final ProviderRoutingService providerRoutingService;
    private final TransactionalOutboxPublisher outboxPublisher;
    private final TransactionTemplate transactionTemplate;

    public WebhookProcessingService(
            WebhookEventRepository webhookRepository,
            DeadLetterEventRepository dlqRepository,
            PaymentRepository paymentRepository,
            ProviderRoutingService providerRoutingService,
            TransactionalOutboxPublisher outboxPublisher,
            PlatformTransactionManager transactionManager) {
        this.webhookRepository = webhookRepository;
        this.dlqRepository = dlqRepository;
        this.paymentRepository = paymentRepository;
        this.providerRoutingService = providerRoutingService;
        this.outboxPublisher = outboxPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${paypulse.webhook.process-interval-ms:1000}")
    public int processPendingWebhooks() {
        List<WebhookEvent> pendingEvents = webhookRepository.findPendingEvents("PENDING", PageRequest.of(0, 20));
        if (pendingEvents.isEmpty()) {
            return 0;
        }

        int processed = 0;
        for (WebhookEvent event : pendingEvents) {
            boolean success = processSingleEvent(event);
            if (success) {
                processed++;
            }
        }
        return processed;
    }

    public boolean processSingleEvent(WebhookEvent event) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            try {
                ProviderId providerId = ProviderId.fromString(event.getProviderId());
                PaymentProviderGateway gateway = providerRoutingService.getGateway(providerId);
                NormalizedWebhookEvent normalized = gateway.parseWebhookPayload(event.getPayload().getBytes(StandardCharsets.UTF_8));

                Optional<Payment> paymentOpt = findPaymentForEvent(normalized);
                if (paymentOpt.isEmpty()) {
                    log.warn("Payment not found for webhook event {}. Will retry.", event.getProviderEventId());
                    event.recordFailure("Payment not yet found in database");
                    webhookRepository.save(event);
                    return false;
                }

                Payment payment = paymentOpt.get();
                PaymentStatus targetStatus = normalized.targetStatus();

                // Check monotonic sequence ordering for out-of-order delivery
                if (payment.getStatus().isSupersededBy(targetStatus)) {
                    log.info("Ignoring out-of-order webhook event {} for payment {}: current state {} supersedes target {}",
                        event.getProviderEventId(), payment.getId(), payment.getStatus(), targetStatus);
                    event.markSuperseded("State already at or beyond target: " + payment.getStatus());
                    webhookRepository.save(event);
                    return true;
                }

                // If valid transition, apply state machine mutation
                if (payment.getStatus().canTransitionTo(targetStatus)) {
                    payment.transitionTo(targetStatus);
                    if (targetStatus == PaymentStatus.SUCCEEDED) {
                        payment.markSucceeded(normalized.providerPaymentId());
                        outboxPublisher.recordEvent(new PaymentDomainEvent.PaymentSucceeded(
                            UUID.randomUUID(),
                            payment.getId(),
                            payment.getMerchantId(),
                            normalized.providerPaymentId(),
                            payment.getMoney(),
                            Instant.now()
                        ));
                    } else if (targetStatus == PaymentStatus.DECLINED) {
                        payment.markDeclined("WEBHOOK_DECLINE", "Declined via provider webhook");
                        outboxPublisher.recordEvent(new PaymentDomainEvent.PaymentDeclined(
                            UUID.randomUUID(),
                            payment.getId(),
                            payment.getMerchantId(),
                            "WEBHOOK_DECLINE",
                            "Declined via provider webhook",
                            Instant.now()
                        ));
                    }
                    paymentRepository.save(payment);
                } else {
                    log.warn("Illegal state transition attempt from {} to {} for payment {}", 
                        payment.getStatus(), targetStatus, payment.getId());
                    event.markSuperseded("Illegal transition rejected by state machine: " + payment.getStatus() + " -> " + targetStatus);
                    webhookRepository.save(event);
                    return true;
                }

                event.markProcessed();
                webhookRepository.save(event);
                return true;

            } catch (Exception ex) {
                log.error("Error processing webhook {}: {}", event.getId(), ex.getMessage(), ex);
                event.recordFailure(ex.getMessage());
                webhookRepository.save(event);

                if ("FAILED".equals(event.getStatus())) {
                    routeToDlq(event, ex);
                }
                return false;
            }
        }));
    }

    private Optional<Payment> findPaymentForEvent(NormalizedWebhookEvent event) {
        if (event.paymentId() != null) {
            return paymentRepository.findById(event.paymentId());
        }
        if (event.providerPaymentId() != null) {
            return paymentRepository.findByProviderIdAndProviderPaymentId(event.providerId().name(), event.providerPaymentId());
        }
        return Optional.empty();
    }

    private void routeToDlq(WebhookEvent event, Throwable cause) {
        try {
            StringWriter sw = new StringWriter();
            cause.printStackTrace(new PrintWriter(sw));
            DeadLetterEvent dlq = new DeadLetterEvent(
                "WEBHOOK",
                event.getId(),
                event.getPayload(),
                event.getSignatureHeader(),
                cause.getClass().getName(),
                cause.getMessage(),
                sw.toString(),
                event.getRetryCount()
            );
            dlqRepository.save(dlq);
            log.error("Routed poisoned webhook {} to Dead-Letter Queue (DLQ ID: {})", event.getId(), dlq.getId());
        } catch (Exception dlqEx) {
            log.error("Fatal failure routing to DLQ: {}", dlqEx.getMessage(), dlqEx);
        }
    }
}
