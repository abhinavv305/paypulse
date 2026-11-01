package com.paypulse.resilience;

import com.paypulse.domain.model.CaptureMethod;
import com.paypulse.domain.model.Money;
import com.paypulse.provider.api.AuthorizationRequest;
import com.paypulse.provider.api.ProviderAuthorizationResult;
import com.paypulse.provider.api.resilience.CircuitBreaker;
import com.paypulse.provider.api.resilience.ResilientPaymentGateway;
import com.paypulse.provider.mock.MockPaymentAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class CircuitBreakerResilienceTest {

    @Test
    @DisplayName("Verify circuit breaker trips to OPEN when error threshold is reached and fast-fails")
    void testCircuitBreakerTripsAndFastFails() {
        MockPaymentAdapter mockAdapter = new MockPaymentAdapter();
        mockAdapter.setSimulateTimeout(true); // Every call will throw timeout

        CircuitBreaker circuitBreaker = new CircuitBreaker(
            "MOCK_TEST",
            5,    // sliding window of 5 calls
            50.0, // 50% threshold
            Duration.ofSeconds(10),
            2
        );

        ResilientPaymentGateway resilientGateway = new ResilientPaymentGateway(
            mockAdapter,
            circuitBreaker,
            new com.paypulse.provider.api.resilience.Bulkhead("MOCK_TEST", 10),
            Duration.ofMillis(500)
        );

        AuthorizationRequest request = new AuthorizationRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "idemp-cb",
            Money.of(1000, "USD"),
            "token",
            CaptureMethod.AUTOMATIC,
            "test@paypulse.io",
            "Test CB",
            Collections.emptyMap()
        );

        // First 5 calls fail due to simulated timeout
        for (int i = 0; i < 5; i++) {
            ProviderAuthorizationResult result = resilientGateway.authorize(request);
            assertThat(result).isInstanceOf(ProviderAuthorizationResult.GatewayError.class);
        }

        // Circuit breaker should now be OPEN!
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 6th call should fast-fail with CIRCUIT_BREAKER_OPEN without even hitting the delegate
        mockAdapter.resetCounts();
        ProviderAuthorizationResult fastFailResult = resilientGateway.authorize(request);
        assertThat(fastFailResult).isInstanceOf(ProviderAuthorizationResult.GatewayError.class);
        ProviderAuthorizationResult.GatewayError ge = (ProviderAuthorizationResult.GatewayError) fastFailResult;
        assertThat(ge.errorCode()).isEqualTo("CIRCUIT_BREAKER_OPEN");
        assertThat(mockAdapter.getChargeCallCount()).isEqualTo(0); // Never reached mock adapter!
    }
}
