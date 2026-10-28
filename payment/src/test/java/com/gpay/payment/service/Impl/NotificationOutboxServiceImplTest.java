package com.gpay.payment.service.Impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.gpay.payment.constant.TransactionStatus;
import com.gpay.payment.constant.TransactionType;
import com.gpay.payment.entity.Transactions;
import com.gpay.payment.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationOutboxServiceImplTest {
    private static final UUID TXN_ID = UUID.randomUUID();
    private static final UUID FROM = UUID.randomUUID();
    private static final UUID TO = UUID.randomUUID();

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    @Mock OutboxEventRepository repository;
    NotificationOutboxServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NotificationOutboxServiceImpl(repository, objectMapper);
    }

    private static Transactions txn(TransactionType type) {
        return Transactions.builder().id(TXN_ID).userId(FROM).type(type).status(TransactionStatus.SUCCESS)
                .amount(new BigDecimal("10000.00")).build();
    }

    @Test
    void transferEventCarriesRecipientAndNominal() throws Exception {
        service.transferSucceeded(txn(TransactionType.TRANSFER), TO);

        ArgumentCaptor<UUID> eventId = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(repository).insertIfAbsent(eventId.capture(), eq(TXN_ID), eq("TRANSFER_SUCCEEDED"),
                payload.capture(), any(), any());

        JsonNode json = objectMapper.readTree(payload.getValue());
        // eventId in the payload == outbox PK, so downstream logs correlate with the outbox row
        assertThat(json.get("eventId").asText()).isEqualTo(eventId.getValue().toString());
        assertThat(json.get("userId").asText()).isEqualTo(FROM.toString());
        assertThat(json.get("counterpartyUserId").asText()).isEqualTo(TO.toString());
        assertThat(json.get("amount").decimalValue()).isEqualByComparingTo("10000.00");
        assertThat(json.get("currency").asText()).isEqualTo("IDR");
    }

    @Test
    void topupEventHasNoCounterparty() throws Exception {
        service.topupSucceeded(txn(TransactionType.TOPUP));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(repository).insertIfAbsent(any(), eq(TXN_ID), eq("TOPUP_SUCCEEDED"), payload.capture(), any(), any());
        assertThat(objectMapper.readTree(payload.getValue()).get("counterpartyUserId").isNull()).isTrue();
    }
}
