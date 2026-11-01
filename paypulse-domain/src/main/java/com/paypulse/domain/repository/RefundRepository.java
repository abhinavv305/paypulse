package com.paypulse.domain.repository;

import com.paypulse.domain.model.Refund;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository extends Repository<Refund, UUID> {

    Refund save(Refund refund);

    Optional<Refund> findById(UUID id);

    List<Refund> findByPaymentIdOrderByCreatedAtDesc(UUID paymentId);
}
