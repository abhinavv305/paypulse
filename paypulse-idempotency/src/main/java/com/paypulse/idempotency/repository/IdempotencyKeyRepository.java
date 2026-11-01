package com.paypulse.idempotency.repository;

import com.paypulse.idempotency.model.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

    Optional<IdempotencyKey> findByMerchantIdAndKey(UUID merchantId, String key);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT k FROM IdempotencyKey k WHERE k.merchantId = :merchantId AND k.key = :key")
    Optional<IdempotencyKey> findByMerchantIdAndKeyForUpdate(@Param("merchantId") UUID merchantId, @Param("key") String key);
}
