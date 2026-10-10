package com.gpay.notification.service;

import com.gpay.notification.dto.NotificationDTO.NotificationResponse;
import com.gpay.notification.dto.NotificationDTO.PageResponse;
import com.gpay.notification.dto.NotificationDTO.PaymentEvent;

import java.util.UUID;

public interface NotificationService {
    /** Idempotent; returns how many notifications were newly created (0 on redelivery). */
    int ingest(PaymentEvent event);

    PageResponse<NotificationResponse> list(UUID userId, int page, int size);

    long unreadCount(UUID userId);

    void markRead(UUID userId, UUID notificationId);

    int markAllRead(UUID userId);
}
