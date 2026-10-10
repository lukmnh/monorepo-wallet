package com.gpay.payment.service.Impl;

import com.gpay.payment.client.AuditClient;
import com.gpay.payment.client.PaymentGatewayClient;
import com.gpay.payment.constant.TransactionStatus;
import com.gpay.payment.constant.TransactionType;
import com.gpay.payment.dto.PaymentDTO;
import com.gpay.payment.dto.PaymentDTO.TransactionResponse;
import com.gpay.payment.entity.TopupRequest;
import com.gpay.payment.entity.Transactions;
import com.gpay.payment.repository.TopUpRequestRepository;
import com.gpay.payment.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TopupServiceImplTest {
    private static final UUID USER = UUID.randomUUID();
    private static final UUID TXN_ID = UUID.randomUUID();
    private static final UUID TOPUP_REQ_ID = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("50000");
    private static final String KEY = "idem-topup";

    @Mock TransactionRepository transactionRepository;
    @Mock TopUpRequestRepository topupRequestRepository;
    @Mock PaymentGatewayClient gatewayClient;
    @Mock AuditClient auditClient;
    @Mock PlatformTransactionManager transactionManager;

    TopupServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TopupServiceImpl(transactionRepository, topupRequestRepository, gatewayClient,
                auditClient, transactionManager, 60);
    }

    private void stubPendingCreated() {
        when(transactionRepository.findByUserIdAndIdempotencyKey(USER, KEY)).thenReturn(Optional.empty());
        when(transactionRepository.save(any())).thenAnswer(inv -> {
            Transactions t = inv.getArgument(0);
            t.setId(TXN_ID);
            return t;
        });
        when(topupRequestRepository.save(any())).thenAnswer(inv -> {
            TopupRequest r = inv.getArgument(0);
            r.setId(TOPUP_REQ_ID);
            return r;
        });
    }

    private static Transactions stored(TransactionStatus status) {
        return Transactions.builder().id(TXN_ID).userId(USER).type(TransactionType.TOPUP).status(status).amount(AMOUNT).build();
    }

    private static PaymentDTO.TopupRequest request() {
        return new PaymentDTO.TopupRequest(AMOUNT, "SUCCESS", null);
    }

    @Test
    void acceptedByGatewayStoresRefAndStaysPendingForWebhook() {
        stubPendingCreated();
        when(gatewayClient.requestTopup(TXN_ID, AMOUNT, "SUCCESS")).thenReturn(Map.of("gatewayRef", "GW-1"));
        when(transactionRepository.findById(TXN_ID)).thenReturn(Optional.of(stored(TransactionStatus.PENDING)));

        TransactionResponse res = service.topup(USER, request(), KEY);

        assertThat(res.status()).isEqualTo(TransactionStatus.PENDING);
        verify(topupRequestRepository).assignGatewayRefIfAbsent(TOPUP_REQ_ID, "GW-1");
        // Wallet is never credited here: only the signed webhook may do that
        verify(transactionRepository, never()).resolvePending(any(), any(), any(), any());
    }

    @Test
    void gatewayRejectionMarksFailed() {
        stubPendingCreated();
        when(gatewayClient.requestTopup(any(), any(), any()))
                .thenThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "400", null, null, null));
        when(transactionRepository.findById(TXN_ID)).thenReturn(Optional.of(stored(TransactionStatus.FAILED)));

        TransactionResponse res = service.topup(USER, request(), KEY);

        assertThat(res.status()).isEqualTo(TransactionStatus.FAILED);
        verify(transactionRepository).resolvePending(eq(TXN_ID), eq(TransactionStatus.FAILED),
                contains("Gateway rejected"), any());
    }

    @Test
    void gatewayTimeoutLeavesPending() {
        stubPendingCreated();
        when(gatewayClient.requestTopup(any(), any(), any())).thenThrow(new ResourceAccessException("timeout"));
        when(transactionRepository.findById(TXN_ID)).thenReturn(Optional.of(stored(TransactionStatus.PENDING)));

        TransactionResponse res = service.topup(USER, request(), KEY);

        assertThat(res.status()).isEqualTo(TransactionStatus.PENDING);
        verify(transactionRepository, never()).resolvePending(any(), any(), any(), any());
    }

    @Test
    void repeatedKeyReplaysWithoutCallingGateway() {
        when(transactionRepository.findByUserIdAndIdempotencyKey(USER, KEY))
                .thenReturn(Optional.of(stored(TransactionStatus.SUCCESS)));

        TransactionResponse res = service.topup(USER, request(), KEY);

        assertThat(res.status()).isEqualTo(TransactionStatus.SUCCESS);
        verifyNoInteractions(gatewayClient);
    }
}
