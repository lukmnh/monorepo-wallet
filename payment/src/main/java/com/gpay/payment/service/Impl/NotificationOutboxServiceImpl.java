package com.gpay.payment.service.Impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpay.payment.constant.OutboxEventType;
import com.gpay.payment.dto.PaymentDTO.PaymentSucceededEvent;
import com.gpay.payment.entity.Transactions;
import com.gpay.payment.repository.OutboxEventRepository;
import com.gpay.payment.service.NotificationOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationOutboxServiceImpl implements NotificationOutboxService {
    private static final String CURRENCY = "IDR";

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    // MANDATORY: fail loudly if a caller forgets the surrounding TX (the event would no longer be atomic)
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void topupSucceeded(Transactions txn) {
        record(txn, OutboxEventType.TOPUP_SUCCEEDED, null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void transferSucceeded(Transactions txn, UUID toUserId) {
        record(txn, OutboxEventType.TRANSFER_SUCCEEDED, toUserId);
    }

    private void record(Transactions txn, OutboxEventType type, UUID counterpartyUserId) {
        UUID eventId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        PaymentSucceededEvent event = new PaymentSucceededEvent(eventId, type, txn.getId(), txn.getUserId(),
                counterpartyUserId, txn.getAmount(), CURRENCY, now);

        int inserted = outboxRepository.insertIfAbsent(eventId, txn.getId(), type.name(), toJson(event),
                MDC.get("traceId"), now);
        if (inserted == 0) {
            log.info("Outbox event {} already recorded for txnId={}, skipping", type, txn.getId());
        }
    }

    private String toJson(PaymentSucceededEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // Rolls back the SUCCESS transition too; webhook redelivery / transfer reconciler retries it
            throw new IllegalStateException("Cannot serialize outbox event for txnId=" + event.transactionId(), e);
        }
    }
}
