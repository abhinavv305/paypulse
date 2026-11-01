package com.paypulse.idempotency.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypulse.domain.exception.IdempotencyConflictException;
import com.paypulse.domain.exception.PayloadHashMismatchException;
import com.paypulse.idempotency.lock.RedisDistributedLockService;
import com.paypulse.idempotency.model.IdempotencyKey;
import com.paypulse.idempotency.repository.IdempotencyKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyKeyRepository repository;
    private final RedisDistributedLockService lockService;
    private final TransactionTemplate requiresNewTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyService(
            IdempotencyKeyRepository repository,
            RedisDistributedLockService lockService,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.lockService = lockService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.objectMapper = objectMapper;
    }

    public static String computeHash(String method, String path, String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String combined = (method != null ? method : "") + ":" + (path != null ? path : "") + ":" + (body != null ? body : "");
            byte[] hash = digest.digest(combined.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm missing", e);
        }
    }

    private record InsertionResult(IdempotencyKey key, boolean newlyInserted) {}

    public <T> IdempotencyExecutionResult<T> executeIdempotent(
            UUID merchantId,
            String idempotencyKey,
            String requestHash,
            Duration leaseDuration,
            Class<T> responseType,
            Supplier<ExecutionOutput<T>> operation) {

        String lockKey = "lock:idemp:" + merchantId + ":" + idempotencyKey;
        String lockToken = lockService.tryAcquireLock(lockKey, leaseDuration);

        if (lockToken == null) {
            // Layer 1 failed: Concurrent duplicate request detected by Redis admission control
            Optional<IdempotencyKey> existing = repository.findByMerchantIdAndKey(merchantId, idempotencyKey);
            if (existing.isPresent()) {
                IdempotencyKey keyRecord = existing.get();
                validatePayloadHash(keyRecord, requestHash, idempotencyKey);
                if (keyRecord.isCompleted()) {
                    return replayCachedResponse(keyRecord, responseType);
                }
            }
            throw new IdempotencyConflictException(
                idempotencyKey,
                "A concurrent payment request with this Idempotency-Key is currently in progress",
                1
            );
        }

        try {
            // Layer 2: PostgreSQL transactional insertion and unique constraint invariant (REQUIRES_NEW)
            InsertionResult insertion;
            try {
                insertion = requiresNewTemplate.execute(status -> {
                    Optional<IdempotencyKey> existingOpt = repository.findByMerchantIdAndKey(merchantId, idempotencyKey);
                    if (existingOpt.isPresent()) {
                        return new InsertionResult(existingOpt.get(), false);
                    }
                    IdempotencyKey newKey = new IdempotencyKey(
                        merchantId,
                        idempotencyKey,
                        requestHash,
                        Instant.now().plus(leaseDuration)
                    );
                    IdempotencyKey saved = repository.saveAndFlush(newKey);
                    return new InsertionResult(saved, true);
                });
            } catch (Exception ex) {
                // If unique constraint violation or conflict occurs
                IdempotencyKey existing = repository.findByMerchantIdAndKey(merchantId, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Failed to load conflicting idempotency record", ex));
                insertion = new InsertionResult(existing, false);
            }

            IdempotencyKey record = insertion.key();
            validatePayloadHash(record, requestHash, idempotencyKey);

            if (record.isCompleted()) {
                return replayCachedResponse(record, responseType);
            }

            if (!insertion.newlyInserted()) {
                // Lost insertion race to a concurrent thread; fail-safe with 409 Conflict
                throw new IdempotencyConflictException(
                    idempotencyKey,
                    "Payment request with this Idempotency-Key is currently being processed",
                    1
                );
            }

            // Winning thread executes primary business operation
            ExecutionOutput<T> output = operation.get();

            // Record completion atomically in database
            requiresNewTemplate.executeWithoutResult(status -> {
                IdempotencyKey toUpdate = repository.findByMerchantIdAndKey(merchantId, idempotencyKey)
                    .orElseThrow();
                try {
                    String jsonBody = output.responseBody() != null 
                        ? (output.responseBody() instanceof String str ? str : objectMapper.writeValueAsString(output.responseBody()))
                        : null;
                    toUpdate.markCompleted(output.resourceId(), output.statusCode(), jsonBody, null);
                    repository.save(toUpdate);
                } catch (Exception e) {
                    log.error("Failed to serialize idempotency response body: {}", e.getMessage(), e);
                    toUpdate.markCompleted(output.resourceId(), output.statusCode(), null, null);
                    repository.save(toUpdate);
                }
            });

            return new IdempotencyExecutionResult<>(
                output.statusCode(),
                output.responseBody(),
                false
            );

        } finally {
            lockService.releaseLock(lockKey, lockToken);
        }
    }

    private void validatePayloadHash(IdempotencyKey record, String incomingHash, String key) {
        if (!record.getRequestHash().equals(incomingHash)) {
            throw new PayloadHashMismatchException(
                key,
                "The Idempotency-Key was previously used with a different request payload"
            );
        }
    }

    private <T> IdempotencyExecutionResult<T> replayCachedResponse(IdempotencyKey record, Class<T> responseType) {
        try {
            T body = null;
            if (record.getResponseBody() != null) {
                if (responseType.equals(String.class)) {
                    body = (T) record.getResponseBody();
                } else {
                    body = objectMapper.readValue(record.getResponseBody(), responseType);
                }
            }
            return new IdempotencyExecutionResult<>(
                record.getResponseCode() != null ? record.getResponseCode() : 200,
                body,
                true
            );
        } catch (Exception e) {
            log.error("Failed to deserialize cached response: {}", e.getMessage(), e);
            return new IdempotencyExecutionResult<>(record.getResponseCode() != null ? record.getResponseCode() : 200, null, true);
        }
    }

    public record ExecutionOutput<T>(
        UUID resourceId,
        int statusCode,
        T responseBody
    ) {}

    public record IdempotencyExecutionResult<T>(
        int statusCode,
        T responseBody,
        boolean isReplay
    ) {}
}
