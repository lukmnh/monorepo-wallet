package com.gpay.auth.scheduler;

import com.gpay.auth.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenCleanupJob {
    private final RefreshTokenRepository refreshTokenRepository;

    // Only expired rows are deleted. Revoked-but-unexpired rows are kept on purpose:
    // they are what lets refresh-token reuse detection recognize a replayed token.
    @Scheduled(cron = "${jwt.cleanup-cron}")
    public void deleteExpiredTokens() {
        int deleted = refreshTokenRepository.deleteExpired(LocalDateTime.now());
        log.info("Refresh token cleanup: {} expired rows deleted", deleted);
    }
}
