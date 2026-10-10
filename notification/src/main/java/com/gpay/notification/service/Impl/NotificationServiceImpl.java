package com.gpay.notification.service.Impl;

import com.gpay.notification.dto.NotificationDTO.NotificationResponse;
import com.gpay.notification.dto.NotificationDTO.PageResponse;
import com.gpay.notification.dto.NotificationDTO.PaymentEvent;
import com.gpay.notification.entity.Notification;
import com.gpay.notification.exception.NotificationException;
import com.gpay.notification.repository.NotificationRepository;
import com.gpay.notification.service.NotificationService;
import com.gpay.notification.service.common.NotificationTemplates;
import com.gpay.notification.service.push.PushDispatcher.NotificationCreated;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {
    private final NotificationRepository notificationRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public int ingest(PaymentEvent event) {
        List<Notification> drafts = switch (event.eventType()) {
            case TOPUP_SUCCEEDED -> List.of(NotificationTemplates.topupSuccess(event));
            case TRANSFER_SUCCEEDED -> {
                if (event.counterpartyUserId() == null) {
                    throw new NotificationException.InvalidEventException("counterpartyUserId is required for TRANSFER_SUCCEEDED");
                }
                // Both sides of a P2P transfer get their own entry, like every mainstream e-wallet
                yield List.of(NotificationTemplates.transferSent(event), NotificationTemplates.transferReceived(event));
            }
        };

        int created = 0;
        for (Notification n : drafts) {
            if (notificationRepository.insertIfAbsent(n) == 1) {
                created++;
                eventPublisher.publishEvent(new NotificationCreated(n));
            }
        }
        log.info("Ingested eventId={} type={} txnId={} created={}",
                event.eventId(), event.eventType(), event.transactionId(), created);
        return created;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(UUID userId, int page, int size) {
        Page<Notification> result = notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size));
        return new PageResponse<>(
                result.getContent().stream().map(NotificationResponse::from).toList(),
                page, size,
                result.getTotalElements(),
                result.getTotalPages(),
                result.isLast()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return notificationRepository.countByUserIdAndReadFalse(userId);
    }

    @Override
    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        // 0 rows = already read (idempotent success) or not this user's (404, same as missing: no ID probing)
        if (notificationRepository.markRead(notificationId, userId, LocalDateTime.now()) == 0
                && !notificationRepository.existsByIdAndUserId(notificationId, userId)) {
            throw new NotificationException.NotificationNotFoundException("Notification not found");
        }
    }

    @Override
    @Transactional
    public int markAllRead(UUID userId) {
        return notificationRepository.markAllRead(userId, LocalDateTime.now());
    }
}
