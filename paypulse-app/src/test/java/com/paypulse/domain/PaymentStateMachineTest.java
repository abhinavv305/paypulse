package com.paypulse.domain;

import com.paypulse.domain.exception.IllegalPaymentStateException;
import com.paypulse.domain.model.CaptureMethod;
import com.paypulse.domain.model.Money;
import com.paypulse.domain.model.Payment;
import com.paypulse.domain.model.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class PaymentStateMachineTest {

    @Test
    @DisplayName("Verify legal lifecycle transitions: CREATED -> AUTHORIZING -> SUCCEEDED -> REFUNDING -> REFUNDED")
    void testLegalStateTransitions() {
        Payment payment = new Payment(
            UUID.randomUUID(),
            null,
            "STRIPE",
            Money.of(10000, "USD"),
            CaptureMethod.AUTOMATIC,
            "user@example.com",
            "Order 1"
        );

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CREATED);

        payment.markAuthorizing();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZING);

        payment.markSucceeded("ch_123");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getProviderPaymentId()).isEqualTo("ch_123");

        payment.markRefunding();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDING);

        payment.markRefunded();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getStatus().isTerminal()).isTrue();
    }

    @Test
    @DisplayName("Verify illegal transitions throw IllegalPaymentStateException")
    void testIllegalTransitionThrowsException() {
        Payment payment = new Payment(
            UUID.randomUUID(),
            null,
            "MOCK",
            Money.of(5000, "USD"),
            CaptureMethod.AUTOMATIC,
            "user@example.com",
            "Order 2"
        );

        payment.markAuthorizing();
        payment.markDeclined("INSUFFICIENT_FUNDS", "Declined");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(payment.getStatus().isTerminal()).isTrue();

        // Terminal state cannot transition to AUTHORIZING or SUCCEEDED
        assertThatThrownBy(payment::markAuthorizing)
            .isInstanceOf(IllegalPaymentStateException.class)
            .hasMessageContaining("Illegal state transition from DECLINED to AUTHORIZING");
    }

    @Test
    @DisplayName("Verify monotonic sequence number superseding logic for webhooks")
    void testStateSuperseding() {
        // SUCCEEDED has sequence 50 (terminal)
        assertThat(PaymentStatus.SUCCEEDED.isSupersededBy(PaymentStatus.AUTHORIZING)).isTrue();
        assertThat(PaymentStatus.SUCCEEDED.isSupersededBy(PaymentStatus.CREATED)).isTrue();
        // REFUNDED has sequence 60 (terminal)
        assertThat(PaymentStatus.REFUNDED.isSupersededBy(PaymentStatus.SUCCEEDED)).isTrue();
    }
}
