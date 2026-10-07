package com.gpay.auth.service.common;

import com.gpay.auth.exception.AuthException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Login brute-force protection backed by Redis.
 * Counts failed logins per username and per client IP inside a fixed window; once a limit is hit,
 * every login for that username/IP is rejected (even with the right password) until the window expires.
 * Unknown usernames are counted too, so lockout behavior does not reveal which accounts exist.
 */
@Service
@Slf4j
public class LoginAttemptService {
    private static final String USER_PREFIX = "login:fail:user:";
    private static final String IP_PREFIX = "login:fail:ip:";

    // INCR and set the window TTL in one atomic step: a counter can never be left without expiry
    private static final RedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return c",
            Long.class);

    private final StringRedisTemplate redis;
    private final int maxAttemptsPerUser;
    private final int maxAttemptsPerIp;
    private final Duration lockDuration;

    public LoginAttemptService(StringRedisTemplate redis,
                               @Value("${login.max-attempts-per-user}") int maxAttemptsPerUser,
                               @Value("${login.max-attempts-per-ip}") int maxAttemptsPerIp,
                               @Value("${login.lock-minutes}") long lockMinutes) {
        this.redis = redis;
        this.maxAttemptsPerUser = maxAttemptsPerUser;
        this.maxAttemptsPerIp = maxAttemptsPerIp;
        this.lockDuration = Duration.ofMinutes(lockMinutes);
    }

    /** @throws AuthException.TooManyLoginAttemptsException if the username or IP is locked out */
    public void assertNotBlocked(String username, String clientIp) {
        try {
            assertBelowLimit(USER_PREFIX + username, maxAttemptsPerUser);
            assertBelowLimit(IP_PREFIX + clientIp, maxAttemptsPerIp);
        } catch (DataAccessException e) {
            // Fail open: Redis outage must not take login down; logged loudly for alerting
            log.error("Login lockout check skipped, Redis unavailable: {}", e.getMessage());
        }
    }

    public void recordFailure(String username, String clientIp) {
        try {
            String ttl = String.valueOf(lockDuration.toSeconds());
            redis.execute(INCR_WITH_TTL, List.of(USER_PREFIX + username), ttl);
            redis.execute(INCR_WITH_TTL, List.of(IP_PREFIX + clientIp), ttl);
        } catch (DataAccessException e) {
            log.error("Failed login not recorded, Redis unavailable: {}", e.getMessage());
        }
    }

    /** A successful login clears the per-user counter; the per-IP counter keeps running. */
    public void recordSuccess(String username) {
        try {
            redis.delete(USER_PREFIX + username);
        } catch (DataAccessException e) {
            log.error("Login counter not reset, Redis unavailable: {}", e.getMessage());
        }
    }

    private void assertBelowLimit(String key, int limit) {
        String value = redis.opsForValue().get(key);
        if (value != null && Long.parseLong(value) >= limit) {
            Long ttl = redis.getExpire(key);
            long retryAfter = ttl != null && ttl > 0 ? ttl : lockDuration.toSeconds();
            throw new AuthException.TooManyLoginAttemptsException(
                    "Too many failed login attempts. Try again in " + retryAfter + " seconds.", retryAfter);
        }
    }
}
