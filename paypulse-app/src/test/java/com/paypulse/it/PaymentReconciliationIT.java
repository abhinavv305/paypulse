package com.paypulse.it;

import com.paypulse.domain.model.CaptureMethod;
import com.paypulse.domain.model.Money;
import com.paypulse.domain.model.Payment;
import com.paypulse.domain.model.PaymentStatus;
import com.paypulse.domain.repository.PaymentRepository;
import com.paypulse.provider.mock.MockPaymentAdapter;
import com.paypulse.reconciliation.model.ReconciliationAuditLog;
import com.paypulse.reconciliation.repository.ReconciliationAuditLogRepository;
import com.paypulse.reconciliation.worker.PaymentReconciliationWorker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
public class PaymentReconciliationIT {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private MockPaymentAdapter mockPaymentAdapter;

    @Autowired
    private PaymentReconciliationWorker reconciliationWorker;

    @Autowired
    private ReconciliationAuditLogRepository auditLogRepository;

    @Test
    @DisplayName("Verify reconciliation worker discovers stuck AUTHORIZING payment and heals it to SUCCEEDED")
    void testReconciliationWorkerHealsStuckPayment() {
        // 1. Create a payment stuck in AUTHORIZING
        Payment payment = new Payment(
            UUID.randomUUID(),
            null,
            "MOCK",
            Money.of(8900, "USD"),
            CaptureMethod.AUTOMATIC,
            "reconcile@paypulse.io",
            "Stuck payment"
        );
        payment.markAuthorizing();
        // Set provider transaction reference that exists in mock adapter
        String mockTxnId = "mock_recon_" + UUID.randomUUID();
        payment.setProviderPaymentId(mockTxnId);
        payment = paymentRepository.save(payment);

        // Preload mock adapter database so queryStatus returns Found(SUCCEEDED)
        mockPaymentAdapter.capture(new com.paypulse.provider.api.CaptureRequest(
            payment.getId(),
            payment.getMerchantId(),
            mockTxnId,
            payment.getMoney(),
            "cap_idemp"
        ));

        // 2. Run reconciliation worker
        int healedCount = reconciliationWorker.reconcileStuckPaymentsInternal(Duration.ofSeconds(-1)); // -1 means all qualify

        // 3. Verify payment was healed to SUCCEEDED
        Payment healedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(healedPayment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        // 4. Verify audit log entry created
        List<ReconciliationAuditLog> auditLogs = auditLogRepository.findByPaymentIdOrderByCreatedAtDesc(payment.getId());
        assertThat(auditLogs).isNotEmpty();
        ReconciliationAuditLog logEntry = auditLogs.getFirst();
        assertThat(logEntry.getInitialStatus()).isEqualTo("AUTHORIZING");
        assertThat(logEntry.getResolvedStatus()).isEqualTo("SUCCEEDED");
        assertThat(logEntry.getActionTaken()).isEqualTo("HEALED_TO_SUCCEEDED");
    }
}
