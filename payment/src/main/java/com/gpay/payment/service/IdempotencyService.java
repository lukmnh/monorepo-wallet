package com.gpay.payment.service;

import java.util.Optional;
import java.util.UUID;

/** Idempotency keys are scoped per user: the same key from two users never collides. */
public interface IdempotencyService {
    boolean tryLock(UUID userId, String idempotencyKey);
    <T> void saveResponse(UUID userId, String idempotencyKey, T response);
    Optional<String> getResponse(UUID userId, String idempotencyKey);
    boolean exists(UUID userId, String idempotencyKey);
}
