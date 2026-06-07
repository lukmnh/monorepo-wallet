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
    public boolean tryLock(String idempotencyKey) {
        String lockKey = LOCK_PREFIX + idempotencyKey;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "PROCESSING", LOCK_TTL);
        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public <T> void saveResponse(String idempotencyKey, T response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(PREFIX + idempotencyKey, json, RESPONSE_TTL);
            redisTemplate.opsForValue().set(LOCK_PREFIX + idempotencyKey, "DONE", RESPONSE_TTL);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize idempotency response for key={}", idempotencyKey, e);
        }
    }

    @Override
    public Optional<String> getResponse(String idempotencyKey) {
        String value = redisTemplate.opsForValue().get(PREFIX + idempotencyKey);
        return Optional.ofNullable(value);
    }

    @Override
    public boolean exists(String idempotencyKey) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(LOCK_PREFIX + idempotencyKey))
                || Boolean.TRUE.equals(redisTemplate.hasKey(PREFIX + idempotencyKey));
    }
}
