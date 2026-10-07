package com.gpay.payment.service.Impl;

import com.gpay.payment.service.RateLimitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Redis-backed limits. Every read-modify-write runs as a single Lua script, so concurrent requests
 * cannot both pass a check, and no key is ever left without a TTL.
 * Daily totals are stored as integer cents (INCRBY), avoiding floating-point drift.
 */
@Service
@Slf4j
public class RateLimitServiceImpl implements RateLimitService {
    private static final String RATE_KEY_PREFIX = "rate:payment:";
    private static final String DAILY_KEY_PREFIX = "daily:transfer-cents:";
    private static final Duration RATE_WINDOW = Duration.ofSeconds(60);
    private static final Duration DAILY_TTL = Duration.ofHours(25);

    private static final RedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return c",
            Long.class);

    // ARGV: amountCents, limitCents, ttlSeconds. Returns 1 if reserved, 0 if it would exceed the limit.
    private static final RedisScript<Long> RESERVE_DAILY = new DefaultRedisScript<>(
            "local v = redis.call('INCRBY', KEYS[1], ARGV[1]) "
                    + "if v > tonumber(ARGV[2]) then redis.call('DECRBY', KEYS[1], ARGV[1]) return 0 end "
                    + "redis.call('EXPIRE', KEYS[1], ARGV[3]) "
                    + "return 1",
            Long.class);

    // Only decrement an existing key: never create a negative, TTL-less counter for an expired day
    private static final RedisScript<Long> RELEASE_DAILY = new DefaultRedisScript<>(
            "if redis.call('EXISTS', KEYS[1]) == 1 then return redis.call('DECRBY', KEYS[1], ARGV[1]) end return 0",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final int rateLimitPerMinute;
    private final long dailyLimitCents;

    public RateLimitServiceImpl(StringRedisTemplate redisTemplate,
                                @Value("${payment.rate-limit-per-minute}") int rateLimitPerMinute,
                                @Value("${payment.daily-transfer-limit}") BigDecimal dailyTransferLimit) {
        this.redisTemplate = redisTemplate;
        this.rateLimitPerMinute = rateLimitPerMinute;
        this.dailyLimitCents = toCents(dailyTransferLimit);
    }

    @Override
    public boolean tryAcquire(UUID userId) {
        String key = RATE_KEY_PREFIX + userId + ":" + System.currentTimeMillis() / 60_000;
        try {
            Long count = redisTemplate.execute(INCR_WITH_TTL, List.of(key), String.valueOf(RATE_WINDOW.toSeconds()));
            if (count != null && count > rateLimitPerMinute) {
                log.warn("Rate limit exceeded for userId={}, count={}", userId, count);
                return false;
            }
            return true;
        } catch (DataAccessException e) {
            // Fail open: request throttling is not a money control
            log.error("Rate limit check skipped, Redis unavailable: {}", e.getMessage());
            return true;
        }
    }

    @Override
    public int getLimitPerMinute() {
        return rateLimitPerMinute;
    }

    // Money control: Redis errors propagate (fail closed)
    @Override
    public boolean tryReserveDailyTransfer(UUID userId, BigDecimal amount, LocalDate day) {
        Long reserved = redisTemplate.execute(RESERVE_DAILY, List.of(dailyKey(userId, day)),
                String.valueOf(toCents(amount)), String.valueOf(dailyLimitCents), String.valueOf(DAILY_TTL.toSeconds()));
        boolean ok = reserved != null && reserved == 1L;
        if (!ok) log.warn("Daily transfer limit exceeded userId={} requested={}", userId, amount);
        return ok;
    }

    @Override
    public void releaseDailyTransfer(UUID userId, BigDecimal amount, LocalDate day) {
        try {
            redisTemplate.execute(RELEASE_DAILY, List.of(dailyKey(userId, day)), String.valueOf(toCents(amount)));
        } catch (DataAccessException e) {
            // Errs on the safe side: the user's remaining daily limit is understated until the key expires
            log.error("Daily limit reservation not released userId={} amount={}: {}", userId, amount, e.getMessage());
        }
    }

    @Override
    public BigDecimal getRemainingDailyLimit(UUID userId, LocalDate day) {
        String current = redisTemplate.opsForValue().get(dailyKey(userId, day));
        long used = current != null ? Long.parseLong(current) : 0L;
        return BigDecimal.valueOf(Math.max(0, dailyLimitCents - used), 2);
    }

    private static String dailyKey(UUID userId, LocalDate day) {
        return DAILY_KEY_PREFIX + userId + ":" + day;
    }

    // Amounts are validated to at most 2 decimals (@Digits), so this is exact
    private static long toCents(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }
}
