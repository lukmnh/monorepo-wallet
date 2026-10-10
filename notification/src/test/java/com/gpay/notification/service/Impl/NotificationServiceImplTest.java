package com.gpay.notification.service.Impl;

import com.gpay.notification.constant.NotificationType;
import com.gpay.notification.constant.PaymentEventType;
import com.gpay.notification.dto.NotificationDTO.PaymentEvent;
import com.gpay.notification.entity.Notification;
import com.gpay.notification.exception.NotificationException;
import com.gpay.notification.repository.NotificationRepository;
import com.gpay.notification.service.push.PushDispatcher.NotificationCreated;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {
    private static final UUID SENDER = UUID.randomUUID();
    private static final UUID RECIPIENT = UUID.randomUUID();

    @Mock NotificationRepository repository;
    @Mock ApplicationEventPublisher publisher;
    @InjectMocks NotificationServiceImpl service;

    private static PaymentEvent event(PaymentEventType type, UUID counterparty) {
        return new PaymentEvent(UUID.randomUUID(), type, UUID.randomUUID(), SENDER, counterparty,
                new BigDecimal("50000.00"), "IDR", LocalDateTime.now());
    }

    @Test
    void topupCreatesOneNotificationForPayerAndTriggersPush() {
        when(repository.insertIfAbsent(any())).thenReturn(1);

        int created = service.ingest(event(PaymentEventType.TOPUP_SUCCEEDED, null));

        assertThat(created).isEqualTo(1);
        ArgumentCaptor<Notification> n = ArgumentCaptor.forClass(Notification.class);
        verify(repository).insertIfAbsent(n.capture());
        assertThat(n.getValue().getUserId()).isEqualTo(SENDER);
        assertThat(n.getValue().getType()).isEqualTo(NotificationType.TOPUP_SUCCESS);
        verify(publisher).publishEvent(any(NotificationCreated.class));
    }

    @Test
    void transferNotifiesSenderAndRecipient() {
        when(repository.insertIfAbsent(any())).thenReturn(1);

        int created = service.ingest(event(PaymentEventType.TRANSFER_SUCCEEDED, RECIPIENT));

        assertThat(created).isEqualTo(2);
        ArgumentCaptor<Notification> n = ArgumentCaptor.forClass(Notification.class);
        verify(repository, times(2)).insertIfAbsent(n.capture());
        assertThat(n.getAllValues()).extracting(Notification::getUserId).containsExactly(SENDER, RECIPIENT);
        assertThat(n.getAllValues()).extracting(Notification::getType)
                .containsExactly(NotificationType.TRANSFER_SENT, NotificationType.TRANSFER_RECEIVED);
        verify(publisher, times(2)).publishEvent(any(NotificationCreated.class));
    }

    @Test
    void redeliveredEventIsDedupedAndDoesNotPushAgain() {
        when(repository.insertIfAbsent(any())).thenReturn(0);   // ON CONFLICT DO NOTHING

        int created = service.ingest(event(PaymentEventType.TRANSFER_SUCCEEDED, RECIPIENT));

        assertThat(created).isZero();
        verifyNoInteractions(publisher);
    }

    @Test
    void transferWithoutRecipientIsRejected() {
        assertThatThrownBy(() -> service.ingest(event(PaymentEventType.TRANSFER_SUCCEEDED, null)))
                .isInstanceOf(NotificationException.InvalidEventException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void markReadOfAnotherUsersNotificationIsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.markRead(eq(id), eq(SENDER), any())).thenReturn(0);
        when(repository.existsByIdAndUserId(id, SENDER)).thenReturn(false);

        assertThatThrownBy(() -> service.markRead(SENDER, id))
                .isInstanceOf(NotificationException.NotificationNotFoundException.class);
    }

    @Test
    void markReadIsIdempotentForAlreadyReadNotification() {
        UUID id = UUID.randomUUID();
        when(repository.markRead(eq(id), eq(SENDER), any())).thenReturn(0);
        when(repository.existsByIdAndUserId(id, SENDER)).thenReturn(true);

        assertThatCode(() -> service.markRead(SENDER, id)).doesNotThrowAnyException();
    }
}
