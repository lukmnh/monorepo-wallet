package com.gpay.payment.constant;

/** Domain events consumed by notification-service (must match its PaymentEventType). */
public enum OutboxEventType {
    TOPUP_SUCCEEDED,
    TRANSFER_SUCCEEDED
}
