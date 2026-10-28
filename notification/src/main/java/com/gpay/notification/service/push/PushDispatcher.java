package com.gpay.notification.service.push;

import com.gpay.notification.entity.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class PushDispatcher {
    private final PushSender pushSender;

    /** Published by the ingest TX for each newly inserted row (never for a deduplicated redelivery). */
    public record NotificationCreated(Notification notification) {}

    /**
     * AFTER_COMMIT: no push for a row that was rolled back. @Async: a slow push provider
     * never delays the ack to payment-service (which would trigger a redelivery).
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(NotificationCreated event) {
        try {
            pushSender.send(event.notification());
        } catch (RuntimeException e) {
            log.warn("Push failed notificationId={}: {}", event.notification().getId(), e.getMessage());
        }
    }
}
