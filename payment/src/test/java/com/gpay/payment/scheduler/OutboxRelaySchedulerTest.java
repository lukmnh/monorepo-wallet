package com.gpay.payment.scheduler;

import com.gpay.payment.client.NotificationClient;
import com.gpay.payment.constant.OutboxStatus;
import com.gpay.payment.entity.OutboxEvent;
import com.gpay.payment.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxRelaySchedulerTest {
    private static final int MAX_ATTEMPTS = 3;

    @Mock OutboxEventRepository repository;
    @Mock NotificationClient client;
    @Mock PlatformTransactionManager transactionManager;

    OutboxRelayScheduler relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelayScheduler(repository, client, transactionManager, 50, MAX_ATTEMPTS);
    }

    private OutboxEvent due(int attempts) {
        OutboxEvent e = OutboxEvent.builder().id(UUID.randomUUID()).aggregateId(UUID.randomUUID())
                .eventType("TOPUP_SUCCEEDED").payload("{\"x\":1}").status(OutboxStatus.PENDING)
                .attempts(attempts).nextAttemptAt(LocalDateTime.now()).build();
        when(repository.lockDue(any(), eq(50))).thenReturn(List.of(e));
        return e;
    }

    @Test
    void deliveredEventIsLeasedThenMarkedPublished() {
        OutboxEvent e = due(0);

        relay.relay();

        verify(repository).lease(eq(List.of(e.getId())), any());
        verify(client).publish("{\"x\":1}");
        verify(repository).markPublished(eq(e.getId()), any());
        verify(repository, never()).recordFailure(any(), any(), anyInt(), any(), any());
    }

    @Test
    void transientFailureSchedulesRetryWithIncrementedAttempts() {
        OutboxEvent e = due(0);
        doThrow(new ResourceAccessException("connection refused")).when(client).publish(any());

        relay.relay();

        verify(repository).recordFailure(eq(e.getId()), eq(OutboxStatus.PENDING), eq(1),
                argThat(next -> next.isAfter(LocalDateTime.now())), contains("connection refused"));
        verify(repository, never()).markPublished(any(), any());
    }

    @Test
    void lastAttemptFailureMarksDead() {
        OutboxEvent e = due(MAX_ATTEMPTS - 1);
        doThrow(new ResourceAccessException("down")).when(client).publish(any());

        relay.relay();

        verify(repository).recordFailure(eq(e.getId()), eq(OutboxStatus.DEAD), eq(MAX_ATTEMPTS), any(), any());
    }

    @Test
    void rejectedPayloadGoesStraightToDead() {
        OutboxEvent e = due(0);
        doThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "400", null, "bad".getBytes(), null))
                .when(client).publish(any());

        relay.relay();

        verify(repository).recordFailure(eq(e.getId()), eq(OutboxStatus.DEAD), eq(1), any(), contains("Rejected"));
    }

    @Test
    void nothingDueDoesNothing() {
        when(repository.lockDue(any(), eq(50))).thenReturn(List.of());

        relay.relay();

        verify(repository, never()).lease(any(), any());
        verifyNoInteractions(client);
    }
}
