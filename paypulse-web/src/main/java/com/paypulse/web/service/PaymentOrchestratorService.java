package com.paypulse.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypulse.domain.event.PaymentDomainEvent;
import com.paypulse.domain.exception.PaymentNotFoundException;
import com.paypulse.domain.model.*;
import com.paypulse.domain.repository.PaymentAttemptRepository;
import com.paypulse.domain.repository.PaymentRepository;
import com.paypulse.domain.repository.RefundRepository;
import com.paypulse.outbox.publisher.TransactionalOutboxPublisher;
import com.paypulse.provider.api.*;
import com.paypulse.web.dto.CapturePaymentRequest;
import com.paypulse.web.dto.CreatePaymentRequest;
import com.paypulse.web.dto.PaymentResponse;
import com.paypulse.web.dto.RefundPaymentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

@Service
public class PaymentOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(PaymentOrchestratorService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final RefundRepository refundRepository;
    private final ProviderRoutingService providerRoutingService;
    private final TransactionalOutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper;

    public PaymentOrchestratorService(
            PaymentRepository paymentRepository,
            PaymentAttemptRepository attemptRepository,
            RefundRepository refundRepository,
            ProviderRoutingService providerRoutingService,
            TransactionalOutboxPublisher outboxPublisher,
            ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
        this.refundRepository = refundRepository;
        this.providerRoutingService = providerRoutingService;
        this.outboxPublisher = outboxPublisher;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PaymentResponse createAndProcessPayment(
            UUID merchantId,
            String idempotencyKey,
            CreatePaymentRequest request) {

        ProviderId providerId = ProviderId.fromString(request.provider());
        Money money = Money.of(request.amount(), request.currency());
        CaptureMethod captureMethod = "MANUAL".equalsIgnoreCase(request.captureMethod()) 
            ? CaptureMethod.MANUAL 
            : CaptureMethod.AUTOMATIC;

        String customerEmail = request.customer() != null ? request.customer().email() : null;
        String token = request.paymentMethod() != null ? request.paymentMethod().token() : null;

        // 1. Create and persist initial payment in CREATED state
        Payment payment = new Payment(
            merchantId,
            null,
            providerId.name(),
            money,
            captureMethod,
            customerEmail,
            request.description()
        );
        payment = paymentRepository.save(payment);

        // 2. Transition to AUTHORIZING and emit outbox event
        payment.markAuthorizing();
        payment = paymentRepository.save(payment);
        outboxPublisher.recordEvent(new PaymentDomainEvent.PaymentAuthorizing(
            UUID.randomUUID(),
            payment.getId(),
            merchantId,
            providerId.name(),
            Instant.now()
        ));

        // 3. Dispatch to Provider Gateway
        PaymentProviderGateway gateway = providerRoutingService.getGateway(providerId);
        AuthorizationRequest authRequest = new AuthorizationRequest(
            payment.getId(),
            merchantId,
            idempotencyKey,
            money,
            token,
            captureMethod,
            customerEmail,
            request.description(),
            Collections.emptyMap()
        );

        long start = System.currentTimeMillis();
        ProviderAuthorizationResult authResult = gateway.authorize(authRequest);
        long latency = System.currentTimeMillis() - start;

        // 4. Handle Gateway Result & Mutate State
        if (authResult instanceof ProviderAuthorizationResult.Success success) {
            if (captureMethod == CaptureMethod.AUTOMATIC) {
                payment.markSucceeded(success.providerReference());
                outboxPublisher.recordEvent(new PaymentDomainEvent.PaymentSucceeded(
                    UUID.randomUUID(),
                    payment.getId(),
                    merchantId,
                    success.providerReference(),
                    money,
                    Instant.now()
                ));
            } else {
                payment.markAuthorized(success.providerReference());
                outboxPublisher.recordEvent(new PaymentDomainEvent.PaymentAuthorized(
                    UUID.randomUUID(),
                    payment.getId(),
                    merchantId,
                    success.providerReference(),
                    money,
                    Instant.now()
                ));
            }
            recordAttempt(payment.getId(), 1, providerId.name(), success.providerReference(), 200, (int) latency, "SUCCESS");

        } else if (authResult instanceof ProviderAuthorizationResult.Declined declined) {
            payment.markDeclined(declined.declineCode().name(), declined.declineMessage());
            outboxPublisher.recordEvent(new PaymentDomainEvent.PaymentDeclined(
                UUID.randomUUID(),
                payment.getId(),
                merchantId,
                declined.declineCode().name(),
                declined.declineMessage(),
                Instant.now()
            ));
            recordAttempt(payment.getId(), 1, providerId.name(), declined.providerReference(), 402, (int) latency, "DECLINED");

        } else if (authResult instanceof ProviderAuthorizationResult.GatewayError error) {
            log.error("Provider error for payment {}: {}", payment.getId(), error.errorMessage());
            payment.markRequiresReconciliation(error.errorMessage());
            recordAttempt(payment.getId(), 1, providerId.name(), null, 502, (int) latency, "GATEWAY_ERROR");
        }

        payment = paymentRepository.save(payment);
        return PaymentResponse.fromDomain(payment);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID paymentId, UUID merchantId) {
        Payment payment = paymentRepository.findByIdAndMerchantId(paymentId, merchantId)
            .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + paymentId));
        return PaymentResponse.fromDomain(payment);
    }

    @Transactional
    public PaymentResponse capturePayment(UUID paymentId, UUID merchantId, CapturePaymentRequest request) {
        Payment payment = paymentRepository.findByIdAndMerchantId(paymentId, merchantId)
            .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + paymentId));

        payment.markCapturing();
        payment = paymentRepository.save(payment);

        ProviderId providerId = ProviderId.fromString(payment.getProviderId());
        PaymentProviderGateway gateway = providerRoutingService.getGateway(providerId);

        CaptureRequest captureRequest = new CaptureRequest(
            payment.getId(),
            merchantId,
            payment.getProviderPaymentId(),
            payment.getMoney(),
            "cap_" + UUID.randomUUID()
        );

        ProviderCaptureResult captureResult = gateway.capture(captureRequest);
        if (captureResult instanceof ProviderCaptureResult.Success success) {
            payment.markSucceeded(success.providerReference());
            outboxPublisher.recordEvent(new PaymentDomainEvent.PaymentSucceeded(
                UUID.randomUUID(),
                payment.getId(),
                merchantId,
                success.providerReference(),
                payment.getMoney(),
                Instant.now()
            ));
        } else {
            payment.markFailed("CAPTURE_FAILED", "Failed to capture funds");
        }

        payment = paymentRepository.save(payment);
        return PaymentResponse.fromDomain(payment);
    }

    @Transactional
    public PaymentResponse cancelPayment(UUID paymentId, UUID merchantId) {
        Payment payment = paymentRepository.findByIdAndMerchantId(paymentId, merchantId)
            .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + paymentId));

        payment.markCancelled();
        payment = paymentRepository.save(payment);

        ProviderId providerId = ProviderId.fromString(payment.getProviderId());
        providerRoutingService.getGateway(providerId).cancel(new CancelRequest(
            payment.getId(),
            merchantId,
            payment.getProviderPaymentId(),
            "cancel_" + UUID.randomUUID()
        ));

        outboxPublisher.recordEvent(new PaymentDomainEvent.PaymentCancelled(
            UUID.randomUUID(),
            payment.getId(),
            merchantId,
            Instant.now()
        ));

        return PaymentResponse.fromDomain(payment);
    }

    @Transactional
    public PaymentResponse refundPayment(UUID paymentId, UUID merchantId, RefundPaymentRequest request) {
        Payment payment = paymentRepository.findByIdAndMerchantId(paymentId, merchantId)
            .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + paymentId));

        long refundAmount = (request.amount() != null) ? request.amount() : payment.getAmountMinorUnits();
        Money refundMoney = Money.of(refundAmount, payment.getCurrency());

        payment.markRefunding();
        payment = paymentRepository.save(payment);

        Refund refund = new Refund(payment.getId(), refundAmount, payment.getCurrency(), request.reason());
        refund = refundRepository.save(refund);

        ProviderId providerId = ProviderId.fromString(payment.getProviderId());
        ProviderRefundResult refundResult = providerRoutingService.getGateway(providerId).refund(new RefundRequest(
            payment.getId(),
            merchantId,
            payment.getProviderPaymentId(),
            refundMoney,
            refund.getId(),
            request.reason(),
            "ref_" + UUID.randomUUID()
        ));

        if (refundResult instanceof ProviderRefundResult.Success success) {
            refund.markSucceeded(success.providerRefundId());
            payment.markRefunded();
            outboxPublisher.recordEvent(new PaymentDomainEvent.PaymentRefunded(
                UUID.randomUUID(),
                payment.getId(),
                merchantId,
                refundMoney,
                success.providerRefundId(),
                Instant.now()
            ));
        } else {
            refund.markFailed("Provider refund rejected");
            payment.transitionTo(PaymentStatus.SUCCEEDED); // revert back to succeeded
        }

        refundRepository.save(refund);
        payment = paymentRepository.save(payment);
        return PaymentResponse.fromDomain(payment);
    }

    private void recordAttempt(UUID paymentId, int attemptNumber, String providerId, String providerTxnId, int status, int latency, String statusStr) {
        PaymentAttempt attempt = new PaymentAttempt(
            paymentId,
            attemptNumber,
            providerId,
            providerTxnId,
            null,
            null,
            status,
            latency,
            statusStr
        );
        attemptRepository.save(attempt);
    }
}
