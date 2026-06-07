package com.gpay.payment.service;

import java.math.BigDecimal;
import java.util.UUID;

public interface RateLimitService {
    boolean checkAndIncrementRateLimit(UUID userId);
    int getRemainingRateLimit(UUID userId);
    boolean checkDailyTransferLimit(UUID userId, BigDecimal amount);
    void incrementDailyTransfer(UUID userId, BigDecimal amount);
    BigDecimal getRemainingDailyLimit(UUID userId);
}
