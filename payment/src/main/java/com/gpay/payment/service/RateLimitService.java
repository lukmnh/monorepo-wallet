package com.gpay.payment.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public interface RateLimitService {
    /** Per-user payment request quota (fixed 1-minute window). */
    boolean tryAcquire(UUID userId);

    int getLimitPerMinute();

    /** Atomically reserves {@code amount} against the user's daily cap for {@code day}; false if it would exceed it. */
    boolean tryReserveDailyTransfer(UUID userId, BigDecimal amount, LocalDate day);

    /** Gives back a reservation of a transfer that definitively failed. */
    void releaseDailyTransfer(UUID userId, BigDecimal amount, LocalDate day);

    BigDecimal getRemainingDailyLimit(UUID userId, LocalDate day);
}
