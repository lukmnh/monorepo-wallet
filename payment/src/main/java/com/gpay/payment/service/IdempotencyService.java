package com.gpay.payment.service;

import java.util.UUID;

/**
 * Short-lived in-flight lock per (user, idempotency key). It only stops concurrent duplicates;
 * replaying a finished request is answered from the DB (UNIQUE (user_id, idempotency_key)).
 */
public interface IdempotencyService {
    /**
     * Validates the key and takes the lock.
     * @return lock token to pass to {@link #release}, or null if another request with this key is in flight
     */
    String tryLock(UUID userId, String idempotencyKey);

    /** Releases the lock only if it is still owned by this token. */
    void release(UUID userId, String idempotencyKey, String lockToken);
}
