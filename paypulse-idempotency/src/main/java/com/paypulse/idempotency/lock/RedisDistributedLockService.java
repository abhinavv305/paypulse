package com.paypulse.idempotency.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

@Service
public class RedisDistributedLockService {

    private static final Logger log = LoggerFactory.getLogger(RedisDistributedLockService.class);

    private static final String RELEASE_LOCK_LUA_SCRIPT = """
        if redis.call("get", KEYS[1]) == ARGV[1] then
            return redis.call("del", KEYS[1])
        else
            return 0
        end
        """;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> releaseScript;

    public RedisDistributedLockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.releaseScript = new DefaultRedisScript<>();
        this.releaseScript.setScriptText(RELEASE_LOCK_LUA_SCRIPT);
        this.releaseScript.setResultType(Long.class);
    }

    /**
     * Attempts to acquire a distributed lock.
     * Returns a non-null token if lock is acquired, or null if lock is already held.
     */
    public String tryAcquireLock(String lockKey, Duration ttl) {
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, token, ttl);
            if (Boolean.TRUE.equals(acquired)) {
                return token;
            }
            return null;
        } catch (Exception ex) {
            // When Redis is unavailable or partition occurs, log degradation and allow DB invariant to guard
            log.warn("Redis lock service degraded for key {}: {}. Falling back to PostgreSQL serialization.", 
                    lockKey, ex.getMessage());
            return "DEGRADED_REDIS_FALLBACK_" + token;
        }
    }

    /**
     * Releases lock only if caller's token matches current token in Redis.
     */
    public boolean releaseLock(String lockKey, String lockToken) {
        if (lockToken == null || lockToken.startsWith("DEGRADED_REDIS_FALLBACK_")) {
            return true;
        }
        try {
            Long result = redisTemplate.execute(
                releaseScript,
                Collections.singletonList(lockKey),
                lockToken
            );
            return result != null && result > 0;
        } catch (Exception ex) {
            log.warn("Failed to release Redis lock {}: {}", lockKey, ex.getMessage());
            return false;
        }
    }
}
