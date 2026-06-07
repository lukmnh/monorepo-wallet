package com.gpay.payment.service;

import java.util.Optional;

public interface IdempotencyService {
    boolean tryLock(String idempotencyKey);
    <T> void saveResponse(String idempotencyKey, T response);
    Optional<String> getResponse(String idempotencyKey);
    boolean exists(String idempotencyKey);
}
