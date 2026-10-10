package com.gpay.notification.constant;

/** Domain events published by payment-service; must match its outbox event types. */
public enum PaymentEventType {
    TOPUP_SUCCEEDED,
    TRANSFER_SUCCEEDED
}
