package com.paypulse.reconciliation.repository;

import com.paypulse.reconciliation.model.ReconciliationAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReconciliationAuditLogRepository extends JpaRepository<ReconciliationAuditLog, UUID> {

    List<ReconciliationAuditLog> findByPaymentIdOrderByCreatedAtDesc(UUID paymentId);
}
