package com.paypulse.domain.repository;

import com.paypulse.domain.model.Payment;
import com.paypulse.domain.model.PaymentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdAndMerchantId(UUID id, UUID merchantId);

    Optional<Payment> findByProviderIdAndProviderPaymentId(String providerId, String providerPaymentId);

    Optional<Payment> findByIdempotencyKeyId(UUID idempotencyKeyId);

    List<Payment> findAllByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    @Query("SELECT p FROM Payment p WHERE p.status IN :statuses AND p.updatedAt < :cutoffTime")
    List<Payment> findStuckPayments(@Param("statuses") List<PaymentStatus> statuses, @Param("cutoffTime") Instant cutoffTime);
}
