package com.paypulse.provider.mock;

import com.paypulse.domain.model.Money;
import com.paypulse.domain.model.PaymentStatus;
import com.paypulse.provider.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class MockPaymentAdapter implements PaymentProviderGateway {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentAdapter.class);

    private final AtomicInteger chargeCallCount = new AtomicInteger(0);
    private final AtomicInteger captureCallCount = new AtomicInteger(0);
    private final AtomicInteger refundCallCount = new AtomicInteger(0);

    private final Map<String, ProviderStatusResult> mockDatabase = new ConcurrentHashMap<>();

    private volatile boolean simulateTimeout = false;
    private volatile boolean simulateDeclined = false;
    private volatile DeclineCode configuredDeclineCode = DeclineCode.INSUFFICIENT_FUNDS;
    private volatile long simulatedLatencyMs = 0;

    @Override
    public ProviderId getProviderId() {
        return ProviderId.MOCK;
    }

    @Override
    public ProviderAuthorizationResult authorize(AuthorizationRequest request) {
        chargeCallCount.incrementAndGet();
        applySimulatedLatency();

        if (simulateTimeout) {
            throw new RuntimeException("Simulated mock gateway timeout");
        }

        if (simulateDeclined) {
            return new ProviderAuthorizationResult.Declined(
                "mock_txn_" + UUID.randomUUID(),
                configuredDeclineCode,
                "Card was declined by mock issuer: " + configuredDeclineCode,
                Map.of("simulated", true)
            );
        }

        String providerTxnId = "mock_auth_" + UUID.randomUUID();
        mockDatabase.put(providerTxnId, new ProviderStatusResult.Found(
            providerTxnId,
            PaymentStatus.AUTHORIZED,
            request.money(),
            Map.of("authCode", "AUTH123456")
        ));

        return new ProviderAuthorizationResult.Success(
            providerTxnId,
            "AUTH123456",
            request.money(),
            Map.of("provider", "MOCK", "status", "APPROVED")
        );
    }

    @Override
    public ProviderCaptureResult capture(CaptureRequest request) {
        captureCallCount.incrementAndGet();
        applySimulatedLatency();

        String providerTxnId = request.providerPaymentId() != null 
            ? request.providerPaymentId() 
            : "mock_cap_" + UUID.randomUUID();

        mockDatabase.put(providerTxnId, new ProviderStatusResult.Found(
            providerTxnId,
            PaymentStatus.SUCCEEDED,
            request.money(),
            Map.of("captured", true)
        ));

        return new ProviderCaptureResult.Success(
            providerTxnId,
            request.money(),
            Map.of("provider", "MOCK", "captured", true)
        );
    }

    @Override
    public ProviderCancelResult cancel(CancelRequest request) {
        applySimulatedLatency();
        mockDatabase.remove(request.providerPaymentId());
        return new ProviderCancelResult.Success(request.providerPaymentId());
    }

    @Override
    public ProviderRefundResult refund(RefundRequest request) {
        refundCallCount.incrementAndGet();
        applySimulatedLatency();

        String refundId = "mock_ref_" + UUID.randomUUID();
        return new ProviderRefundResult.Success(refundId, request.money());
    }

    @Override
    public ProviderStatusResult queryStatus(ProviderQueryRequest request) {
        applySimulatedLatency();
        if (request.providerPaymentId() != null && mockDatabase.containsKey(request.providerPaymentId())) {
            return mockDatabase.get(request.providerPaymentId());
        }
        return new ProviderStatusResult.NotFound(request.providerPaymentId());
    }

    @Override
    public WebhookVerificationResult verifyWebhookSignature(Map<String, String> headers, byte[] rawPayload) {
        String signature = headers.get("X-Mock-Signature");
        if (signature == null || signature.isBlank()) {
            return new WebhookVerificationResult.Invalid("Missing X-Mock-Signature header");
        }
        if ("valid_signature".equals(signature) || signature.startsWith("sig_")) {
            String eventId = signature.startsWith("sig_") ? signature.substring(4) : "mock_evt_123";
            return new WebhookVerificationResult.Valid(eventId, "payment.succeeded");
        }
        return new WebhookVerificationResult.Invalid("Signature verification failed");
    }

    @Override
    public NormalizedWebhookEvent parseWebhookPayload(byte[] rawPayload) {
        String payloadStr = new String(rawPayload, StandardCharsets.UTF_8);
        return new NormalizedWebhookEvent(
            ProviderId.MOCK,
            "mock_evt_" + UUID.randomUUID(),
            "payment.succeeded",
            null,
            "mock_ref_123",
            PaymentStatus.SUCCEEDED,
            Money.of(5000, "USD"),
            Instant.now(),
            payloadStr
        );
    }

    private void applySimulatedLatency() {
        if (simulatedLatencyMs > 0) {
            try {
                Thread.sleep(simulatedLatencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // Diagnostics & Test Harness Getters / Setters
    public int getChargeCallCount() { return chargeCallCount.get(); }
    public int getCaptureCallCount() { return captureCallCount.get(); }
    public int getRefundCallCount() { return refundCallCount.get(); }
    public void resetCounts() {
        chargeCallCount.set(0);
        captureCallCount.set(0);
        refundCallCount.set(0);
        mockDatabase.clear();
        simulateTimeout = false;
        simulateDeclined = false;
        simulatedLatencyMs = 0;
    }
    public void setSimulateTimeout(boolean simulateTimeout) { this.simulateTimeout = simulateTimeout; }
    public void setSimulateDeclined(boolean simulateDeclined, DeclineCode declineCode) {
        this.simulateDeclined = simulateDeclined;
        this.configuredDeclineCode = declineCode;
    }
    public void setSimulatedLatencyMs(long latencyMs) { this.simulatedLatencyMs = latencyMs; }
}
