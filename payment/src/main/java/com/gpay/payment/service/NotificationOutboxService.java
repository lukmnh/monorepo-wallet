package com.gpay.payment.service;

import com.gpay.payment.entity.Transactions;

import java.util.UUID;

/**
 * Records "payment succeeded" events in payment.outbox_events. Must run inside the transaction that
 * moves the payment to SUCCESS, so the event exists if and only if the status change committed.
 */
public interface NotificationOutboxService {
    void topupSucceeded(Transactions txn);

    void transferSucceeded(Transactions txn, UUID toUserId);
}
