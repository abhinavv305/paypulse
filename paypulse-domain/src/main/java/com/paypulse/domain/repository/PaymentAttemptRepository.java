package com.paypulse.domain.repository;

import com.paypulse.domain.model.PaymentAttempt;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

public interface PaymentAttemptRepository extends Repository<PaymentAttempt, UUID> {

    PaymentAttempt save(PaymentAttempt attempt);

    List<PaymentAttempt> findByPaymentIdOrderByAttemptNumberAsc(UUID paymentId);
}
