package com.gpay.payment.scheduler;

import com.gpay.payment.client.NotificationClient;
import com.gpay.payment.constant.OutboxStatus;
import com.gpay.payment.entity.OutboxEvent;
import com.gpay.payment.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Delivers outbox events to notification-service, at least once.
 * Duplicates (lease expiry, lost ack) are harmless: notification-service dedupes on (transaction_id, type).
 */
@Slf4j
@Component
public class OutboxRelayScheduler {
    private static final long LEASE_SECONDS = 60;
    private static final long BASE_BACKOFF_SECONDS = 5;
    private static final long MAX_BACKOFF_SECONDS = 1800;
    private static final int MAX_ERROR_LENGTH = 500;

    private final OutboxEventRepository outboxRepository;
    private final NotificationClient notificationClient;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxRelayScheduler(OutboxEventRepository outboxRepository,
                                NotificationClient notificationClient,
                                PlatformTransactionManager transactionManager,
                                @Value("${payment.outbox-batch-size}") int batchSize,
                                @Value("${payment.outbox-max-attempts}") int maxAttempts) {
        this.outboxRepository = outboxRepository;
        this.notificationClient = notificationClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${payment.outbox-poll-interval-ms}")
    public void relay() {
        for (OutboxEvent event : claimDueBatch()) {
            MDC.put("traceId", event.getTraceId());
            try {
                publish(event);
            } finally {
                MDC.clear();
            }
        }
    }

    /** Short TX: lock due rows, lease them, commit. No DB connection is held during the HTTP calls. */
    private List<OutboxEvent> claimDueBatch() {
        LocalDateTime now = LocalDateTime.now();
        return transactionTemplate.execute(status -> {
            List<OutboxEvent> due = outboxRepository.lockDue(now, batchSize);
            if (!due.isEmpty()) {
                outboxRepository.lease(due.stream().map(OutboxEvent::getId).toList(), now.plusSeconds(LEASE_SECONDS));
            }
            return due;
        });
    }

    private void publish(OutboxEvent event) {
        try {
            notificationClient.publish(event.getPayload());
            outboxRepository.markPublished(event.getId(), LocalDateTime.now());
            log.info("Outbox event published id={} type={} txnId={}", event.getId(), event.getEventType(), event.getAggregateId());
        } catch (HttpClientErrorException.BadRequest e) {
            // Payload rejected: retrying the same bytes can never succeed
            fail(event, OutboxStatus.DEAD, "Rejected: " + e.getResponseBodyAsString());
        } catch (RestClientException e) {
            // Timeout, 5xx, 401/403 (misconfigured key): transient from the event's point of view
            boolean exhausted = event.getAttempts() + 1 >= maxAttempts;
            fail(event, exhausted ? OutboxStatus.DEAD : OutboxStatus.PENDING, e.getMessage());
        }
    }

    private void fail(OutboxEvent event, OutboxStatus status, String error) {
        int attempts = event.getAttempts() + 1;
        LocalDateTime next = LocalDateTime.now().plusSeconds(backoffSeconds(attempts));
        outboxRepository.recordFailure(event.getId(), status, attempts, next, truncate(error));
        if (status == OutboxStatus.DEAD) {
            log.error("Outbox event DEAD id={} txnId={} attempts={}: {}", event.getId(), event.getAggregateId(), attempts, error);
        } else {
            log.warn("Outbox event retry id={} attempt={} next={}: {}", event.getId(), attempts, next, error);
        }
    }

    /** Exponential: 5 s, 10 s, 20 s ... capped at 30 min. */
    private static long backoffSeconds(int attempts) {
        long exp = BASE_BACKOFF_SECONDS << Math.min(attempts - 1, 20);
        return Math.min(exp, MAX_BACKOFF_SECONDS);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= MAX_ERROR_LENGTH ? s : s.substring(0, MAX_ERROR_LENGTH);
    }
}
