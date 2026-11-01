package com.paypulse.reconciliation.worker;

import com.paypulse.domain.event.PaymentDomainEvent;
import com.paypulse.domain.model.Payment;
import com.paypulse.domain.model.PaymentStatus;
import com.paypulse.domain.repository.PaymentRepository;
import com.paypulse.outbox.publisher.TransactionalOutboxPublisher;
import com.paypulse.provider.api.*;
import com.paypulse.reconciliation.model.ReconciliationAuditLog;
import com.paypulse.reconciliation.repository.ReconciliationAuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class PaymentReconciliationWorker {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationWorker.class);

    private final PaymentRepository paymentRepository;
    private final ProviderRoutingService providerRoutingService;
    private final ReconciliationAuditLogRepository auditLogRepository;
    private final TransactionalOutboxPublisher outboxPublisher;
    private final TransactionTemplate transactionTemplate;

    public PaymentReconciliationWorker(
            PaymentRepository paymentRepository,
            ProviderRoutingService providerRoutingService,
            ReconciliationAuditLogRepository auditLogRepository,
            TransactionalOutboxPublisher outboxPublisher,
            PlatformTransactionManager transactionManager) {
        this.paymentRepository = paymentRepository;
        this.providerRoutingService = providerRoutingService;
        this.auditLogRepository = auditLogRepository;
        this.outboxPublisher = outboxPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${paypulse.reconciliation.interval-ms:60000}")
    public int reconcileStuckPayments() {
        return reconcileStuckPaymentsInternal(Duration.ofMinutes(5));
    }

    public int reconcileStuckPaymentsInternal(Duration olderThan) {
        Instant cutoff = Instant.now().minus(olderThan);
        List<PaymentStatus> stuckStatuses = List.of(
            PaymentStatus.AUTHORIZING,
            PaymentStatus.CAPTURING,
            PaymentStatus.REFUNDING,
            PaymentStatus.REQUIRES_RECONCILIATION
        );

        List<Payment> stuckPayments = paymentRepository.findStuckPayments(stuckStatuses, cutoff);
        if (stuckPayments.isEmpty()) {
            return 0;
        }

        int healedCount = 0;
        for (Payment payment : stuckPayments) {
            boolean healed = healPayment(payment.getId());
            if (healed) healedCount++;
        }
        return healedCount;
    }

    private boolean healPayment(UUID paymentId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            Payment payment = paymentRepository.findById(paymentId).orElse(null);
            if (payment == null) return false;

            PaymentStatus initialStatus = payment.getStatus();
            try {
                ProviderId providerId = ProviderId.fromString(payment.getProviderId());
                PaymentProviderGateway gateway = providerRoutingService.getGateway(providerId);

                ProviderQueryRequest queryRequest = new ProviderQueryRequest(
                    payment.getId(),
                    payment.getMerchantId(),
                    payment.getProviderPaymentId(),
                    null
                );

                ProviderStatusResult queryResult = gateway.queryStatus(queryRequest);

                if (queryResult instanceof ProviderStatusResult.Found found) {
                    PaymentStatus providerStatus = found.status();
                    if (providerStatus == PaymentStatus.SUCCEEDED && payment.getStatus().canTransitionTo(PaymentStatus.SUCCEEDED)) {
                        payment.markSucceeded(found.providerReference());
                        paymentRepository.save(payment);

                        outboxPublisher.recordEvent(new PaymentDomainEvent.PaymentReconciled(
                            UUID.randomUUID(),
                            payment.getId(),
                            payment.getMerchantId(),
                            initialStatus,
                            PaymentStatus.SUCCEEDED,
                            providerStatus.name(),
                            "HEALED_TO_SUCCEEDED",
                            Instant.now()
                        ));

                        auditLogRepository.save(new ReconciliationAuditLog(
                            payment.getId(),
                            initialStatus.name(),
                            PaymentStatus.SUCCEEDED.name(),
                            providerStatus.name(),
                            "HEALED_TO_SUCCEEDED",
                            "Provider confirmed payment was successfully captured"
                        ));
                        return true;
                    }
                } else if (queryResult instanceof ProviderStatusResult.NotFound) {
                    payment.markFailed("PROVIDER_TRANSACTION_NOT_FOUND", "No record on provider gateway");
                    paymentRepository.save(payment);

                    auditLogRepository.save(new ReconciliationAuditLog(
                        payment.getId(),
                        initialStatus.name(),
                        PaymentStatus.FAILED.name(),
                        "NOT_FOUND",
                        "HEALED_TO_FAILED",
                        "Transaction never reached or registered at provider gateway"
                    ));
                    return true;
                }

                return false;
            } catch (Exception ex) {
                log.error("Failed reconciling payment {}: {}", payment.getId(), ex.getMessage());
                return false;
            }
        }));
    }
}
