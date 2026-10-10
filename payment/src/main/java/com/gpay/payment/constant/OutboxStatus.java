package com.gpay.payment.constant;

public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    DEAD        // gave up (max attempts or rejected payload); needs manual replay
}
