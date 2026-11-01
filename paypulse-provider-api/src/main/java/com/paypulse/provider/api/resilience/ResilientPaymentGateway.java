package com.paypulse.provider.api.resilience;

import com.paypulse.provider.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;

public class ResilientPaymentGateway implements PaymentProviderGateway {

    private static final Logger log = LoggerFactory.getLogger(ResilientPaymentGateway.class);

    private final PaymentProviderGateway delegate;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final Duration timeout;
    private final ExecutorService executor;

    public ResilientPaymentGateway(
            PaymentProviderGateway delegate,
            CircuitBreaker circuitBreaker,
            Bulkhead bulkhead,
            Duration timeout) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.bulkhead = bulkhead;
        this.timeout = timeout;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public static ResilientPaymentGateway wrap(PaymentProviderGateway delegate) {
        return new ResilientPaymentGateway(
            delegate,
            CircuitBreaker.ofDefaults(delegate.getProviderId().name()),
            new Bulkhead(delegate.getProviderId().name(), 50),
            Duration.ofSeconds(5)
        );
    }

    @Override
    public ProviderId getProviderId() {
        return delegate.getProviderId();
    }

    @Override
    public ProviderAuthorizationResult authorize(AuthorizationRequest request) {
        if (!circuitBreaker.tryAcquirePermission()) {
            log.warn("Circuit breaker is OPEN for provider: {}. Fast-failing payment {}.", delegate.getProviderId(), request.paymentId());
            return new ProviderAuthorizationResult.GatewayError(
                "CIRCUIT_BREAKER_OPEN",
                "Payment provider " + delegate.getProviderId() + " is temporarily unavailable (Circuit Breaker OPEN)",
                false,
                null
            );
        }

        if (!bulkhead.tryAcquire(200)) {
            log.warn("Bulkhead is full for provider: {}. Payment {}.", delegate.getProviderId(), request.paymentId());
            return new ProviderAuthorizationResult.GatewayError(
                "BULKHEAD_FULL",
                "Payment provider " + delegate.getProviderId() + " is saturated",
                true,
                null
            );
        }

        try {
            Future<ProviderAuthorizationResult> future = executor.submit(() -> delegate.authorize(request));
            ProviderAuthorizationResult result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);

            if (result instanceof ProviderAuthorizationResult.GatewayError ge && ge.isRetryable()) {
                circuitBreaker.onError(ge.cause() != null ? ge.cause() : new RuntimeException(ge.errorMessage()));
            } else {
                circuitBreaker.onSuccess();
            }
            return result;
        } catch (TimeoutException te) {
            circuitBreaker.onError(te);
            return new ProviderAuthorizationResult.GatewayError("TIMEOUT", "Gateway call exceeded " + timeout.toMillis() + "ms", true, te);
        } catch (Exception ex) {
            circuitBreaker.onError(ex);
            return new ProviderAuthorizationResult.GatewayError("EXECUTION_ERROR", ex.getMessage(), true, ex);
        } finally {
            bulkhead.release();
        }
    }

    @Override
    public ProviderCaptureResult capture(CaptureRequest request) {
        return delegate.capture(request);
    }

    @Override
    public ProviderCancelResult cancel(CancelRequest request) {
        return delegate.cancel(request);
    }

    @Override
    public ProviderRefundResult refund(RefundRequest request) {
        return delegate.refund(request);
    }

    @Override
    public ProviderStatusResult queryStatus(ProviderQueryRequest request) {
        return delegate.queryStatus(request);
    }

    @Override
    public WebhookVerificationResult verifyWebhookSignature(Map<String, String> headers, byte[] rawPayload) {
        return delegate.verifyWebhookSignature(headers, rawPayload);
    }

    @Override
    public NormalizedWebhookEvent parseWebhookPayload(byte[] rawPayload) {
        return delegate.parseWebhookPayload(rawPayload);
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public Bulkhead getBulkhead() {
        return bulkhead;
    }
}
