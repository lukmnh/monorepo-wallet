package com.gpay.auth.service.common;

import com.gpay.auth.exception.AuthException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoginAttemptServiceTest {
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> ops;

    LoginAttemptService service;

    @BeforeEach
    void setUp() {
        service = new LoginAttemptService(redis, 5, 20, 15);
    }

    @Test
    void blocksUserAtLimitWithRemainingTtlAsRetryAfter() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("login:fail:user:alice")).thenReturn("5");
        when(redis.getExpire("login:fail:user:alice")).thenReturn(420L);

        assertThatThrownBy(() -> service.assertNotBlocked("alice", "1.1.1.1"))
                .isInstanceOf(AuthException.TooManyLoginAttemptsException.class)
                .extracting(e -> ((AuthException.TooManyLoginAttemptsException) e).getRetryAfterSeconds())
                .isEqualTo(420L);
    }

    @Test
    void blocksIpAtLimitEvenForAnotherUsername() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("login:fail:user:bob")).thenReturn(null);
        when(ops.get("login:fail:ip:1.1.1.1")).thenReturn("20");
        when(redis.getExpire("login:fail:ip:1.1.1.1")).thenReturn(-1L);

        // No TTL reported -> falls back to the full lock window (15 min)
        assertThatThrownBy(() -> service.assertNotBlocked("bob", "1.1.1.1"))
                .extracting(e -> ((AuthException.TooManyLoginAttemptsException) e).getRetryAfterSeconds())
                .isEqualTo(900L);
    }

    @Test
    void belowLimitPasses() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("login:fail:user:alice")).thenReturn("4");
        when(ops.get("login:fail:ip:1.1.1.1")).thenReturn("19");

        assertThatCode(() -> service.assertNotBlocked("alice", "1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    void failsOpenWhenRedisIsDown() {
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> service.assertNotBlocked("alice", "1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    void successClearsOnlyUserCounter() {
        service.recordSuccess("alice");

        verify(redis).delete("login:fail:user:alice");
        verifyNoMoreInteractions(redis);
    }
}
