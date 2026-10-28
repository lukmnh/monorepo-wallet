package com.gpay.payment.service.Impl;

import com.gpay.payment.exception.PaymentException;
import com.gpay.payment.service.IdempotencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class IdempotencyServiceImpl implements IdempotencyService {
    private static final String LOCK_PREFIX = "idempotency:lock:";
    // Longer than the slowest request path (wallet: 2 attempts x (3s connect + 10s read))
    private static final Duration LOCK_TTL = Duration.ofSeconds(60);
    private static final int MAX_KEY_LENGTH = 100;   // payment.transactions.idempotency_key VARCHAR(100)

    // Delete only if we still own the lock (it may have expired and been taken by another request)
    private static final RedisScript<Long> RELEASE_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    @Override
    public String tryLock(UUID userId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new PaymentException.InvalidIdempotencyKeyException(
                    "X-Idempotency-Key must be 1-" + MAX_KEY_LENGTH + " characters");
        }
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey(userId, idempotencyKey), token, LOCK_TTL);
            return Boolean.TRUE.equals(acquired) ? token : null;
        } catch (DataAccessException e) {
            // Fail open: the DB unique constraint + replay still guarantee exactly-once
            log.error("Idempotency lock skipped, Redis unavailable: {}", e.getMessage());
            return token;
        }
    }

    @Override
    public void release(UUID userId, String idempotencyKey, String lockToken) {
        try {
            redisTemplate.execute(RELEASE_IF_OWNER, List.of(lockKey(userId, idempotencyKey)), lockToken);
        } catch (DataAccessException e) {
            log.warn("Idempotency lock not released (expires in {}s): {}", LOCK_TTL.toSeconds(), e.getMessage());
        }
    }

    // Mirrors DB constraint UNIQUE (user_id, idempotency_key)
    private static String lockKey(UUID userId, String idempotencyKey) {
        return LOCK_PREFIX + userId + ":" + idempotencyKey;
    }
}
