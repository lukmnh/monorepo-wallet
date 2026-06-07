package com.gpay.payment.service.Impl;

import com.gpay.payment.service.RateLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class RateLimitServiceImpl implements RateLimitService {
    private static final String RATE_KEY_PREFIX = "rate:payment:";
    private static final String DAILY_KEY_PREFIX = "daily:transfer:";

    private final StringRedisTemplate redisTemplate;

    @Value("${payment.rate-limit-per-minute:5}")
    private int rateLimitPerMinute;

    @Value("${payment.daily-transfer-limit:10000000}")
    private BigDecimal dailyTransferLimit;

    @Override
    public boolean checkAndIncrementRateLimit(UUID userId) {
        long epochMinute = System.currentTimeMillis() / 60000;
        String key = RATE_KEY_PREFIX + userId + ":" + epochMinute;

        Long count = redisTemplate.opsForValue().increment(key);
        if (count == 1) {
            redisTemplate.expire(key, Duration.ofSeconds(60));
        }

        if (count > rateLimitPerMinute) {
            log.warn("Rate limit exceeded for userId={}, count={}", userId, count);
            return false;
        }
        return true;
    }

    @Override
    public int getRemainingRateLimit(UUID userId) {
        long epochMinute = System.currentTimeMillis() / 60000;
        String key = RATE_KEY_PREFIX + userId + ":" + epochMinute;
        String val = redisTemplate.opsForValue().get(key);
        int current = val != null ? Integer.parseInt(val) : 0;
        return Math.max(0, rateLimitPerMinute - current);
    }

    @Override
    public boolean checkDailyTransferLimit(UUID userId, BigDecimal amount) {
        String key = dailyKey(userId);
        String currentStr = redisTemplate.opsForValue().get(key);
        BigDecimal current = currentStr != null ? new BigDecimal(currentStr) : BigDecimal.ZERO;

        if (current.add(amount).compareTo(dailyTransferLimit) > 0) {
            log.warn("Daily transfer limit exceeded userId={}, current={}, requested={}, limit={}",
                    userId, current, amount, dailyTransferLimit);
            return false;
        }
        return true;
    }

    @Override
    public void incrementDailyTransfer(UUID userId, BigDecimal amount) {
        String key = dailyKey(userId);
        String currentStr = redisTemplate.opsForValue().get(key);
        BigDecimal current = currentStr != null ? new BigDecimal(currentStr) : BigDecimal.ZERO;
        BigDecimal updated = current.add(amount);
        redisTemplate.opsForValue().set(key, updated.toPlainString(), Duration.ofHours(25));
    }

    @Override
    public BigDecimal getRemainingDailyLimit(UUID userId) {
        String key = dailyKey(userId);
        String currentStr = redisTemplate.opsForValue().get(key);
        BigDecimal current = currentStr != null ? new BigDecimal(currentStr) : BigDecimal.ZERO;
        return dailyTransferLimit.subtract(current).max(BigDecimal.ZERO);
    }

    private String dailyKey(UUID userId) {
        return DAILY_KEY_PREFIX + userId + ":" + LocalDate.now();
    }
}
