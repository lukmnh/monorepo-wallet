package com.gpay.payment.service.Impl;

import com.gpay.payment.exception.PaymentException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Redis-backed controls: rate limit, daily cap (money control), idempotency lock. */
class LimitsAndIdempotencyTest {
    private static final UUID USER = UUID.randomUUID();
    private static final LocalDate DAY = LocalDate.of(2026, 10, 10);

    @Nested
    class RateLimit {
        final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        final RateLimitServiceImpl service = new RateLimitServiceImpl(redis, 5, new BigDecimal("10000000"));

        @Test
        @SuppressWarnings("unchecked")
        void allowsUpToLimitThenBlocks() {
            when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(5L, 6L);

            assertThat(service.tryAcquire(USER)).isTrue();
            assertThat(service.tryAcquire(USER)).isFalse();
        }

        @Test
        @SuppressWarnings("unchecked")
        void rateLimitFailsOpenWhenRedisDown() {
            when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                    .thenThrow(new RedisConnectionFailureException("down"));

            assertThat(service.tryAcquire(USER)).isTrue();
        }

        @Test
        @SuppressWarnings("unchecked")
        void dailyReservationSendsIntegerCentsToTheAtomicScript() {
            when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

            assertThat(service.tryReserveDailyTransfer(USER, new BigDecimal("1000.50"), DAY)).isTrue();

            // amount 1000.50 -> 100050 cents; limit 10,000,000 -> 1,000,000,000 cents; TTL 25 h
            verify(redis).execute(any(RedisScript.class), eq(List.of("daily:transfer-cents:" + USER + ":" + DAY)),
                    eq("100050"), eq("1000000000"), eq("90000"));
        }

        @Test
        @SuppressWarnings("unchecked")
        void dailyReservationRejectedWhenScriptRefuses() {
            when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

            assertThat(service.tryReserveDailyTransfer(USER, BigDecimal.TEN, DAY)).isFalse();
        }

        @Test
        @SuppressWarnings("unchecked")
        void dailyCapFailsClosedWhenRedisDown() {
            when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                    .thenThrow(new RedisConnectionFailureException("down"));

            assertThatThrownBy(() -> service.tryReserveDailyTransfer(USER, BigDecimal.TEN, DAY))
                    .isInstanceOf(RedisConnectionFailureException.class);
        }

        @Test
        @SuppressWarnings("unchecked")
        void remainingLimitIsCapMinusUsedCents() {
            ValueOperations<String, String> ops = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(ops);
            when(ops.get("daily:transfer-cents:" + USER + ":" + DAY)).thenReturn("250000000");   // 2,500,000.00 used

            assertThat(service.getRemainingDailyLimit(USER, DAY)).isEqualByComparingTo("7500000.00");
        }
    }

    @Nested
    class IdempotencyLock {
        final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        final ValueOperations<String, String> ops = mock(ValueOperations.class);
        final IdempotencyServiceImpl service = new IdempotencyServiceImpl(redis);

        @Test
        void acquiredLockReturnsOwnerToken() {
            when(redis.opsForValue()).thenReturn(ops);
            when(ops.setIfAbsent(eq("idempotency:lock:" + USER + ":k1"), anyString(), eq(Duration.ofSeconds(60))))
                    .thenReturn(true);

            assertThat(service.tryLock(USER, "k1")).isNotBlank();
        }

        @Test
        void concurrentDuplicateGetsNoLock() {
            when(redis.opsForValue()).thenReturn(ops);
            when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

            assertThat(service.tryLock(USER, "k1")).isNull();
        }

        @Test
        void redisDownFailsOpenBecauseDbConstraintStillGuardsReplay() {
            when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));

            assertThat(service.tryLock(USER, "k1")).isNotBlank();
        }

        @Test
        void rejectsBlankOrOversizedKey() {
            assertThatThrownBy(() -> service.tryLock(USER, " "))
                    .isInstanceOf(PaymentException.InvalidIdempotencyKeyException.class);
            assertThatThrownBy(() -> service.tryLock(USER, "x".repeat(101)))
                    .isInstanceOf(PaymentException.InvalidIdempotencyKeyException.class);
        }
    }
}
