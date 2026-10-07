package com.gpay.payment.service.Impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpay.payment.service.IdempotencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class IdempotencyServiceImpl implements IdempotencyService {
    private static final String PREFIX = "idempotency:";
    private static final String LOCK_PREFIX = "idempotency:lock:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);
    private static final Duration RESPONSE_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public boolean tryLock(UUID userId, String idempotencyKey) {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(LOCK_PREFIX + scope(userId, idempotencyKey), "PROCESSING", LOCK_TTL);
        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public <T> void saveResponse(UUID userId, String idempotencyKey, T response) {
        String key = scope(userId, idempotencyKey);
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(PREFIX + key, json, RESPONSE_TTL);
            redisTemplate.opsForValue().set(LOCK_PREFIX + key, "DONE", RESPONSE_TTL);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize idempotency response for key={}", key, e);
        }
    }

    @Override
    public Optional<String> getResponse(UUID userId, String idempotencyKey) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(PREFIX + scope(userId, idempotencyKey)));
    }

    @Override
    public boolean exists(UUID userId, String idempotencyKey) {
        String key = scope(userId, idempotencyKey);
        return Boolean.TRUE.equals(redisTemplate.hasKey(LOCK_PREFIX + key))
                || Boolean.TRUE.equals(redisTemplate.hasKey(PREFIX + key));
    }

    // Mirrors DB constraint UNIQUE (user_id, idempotency_key)
    private static String scope(UUID userId, String idempotencyKey) {
        return userId + ":" + idempotencyKey;
    }
}
