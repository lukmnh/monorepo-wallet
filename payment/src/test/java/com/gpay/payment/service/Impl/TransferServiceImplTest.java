package com.gpay.payment.service.Impl;

import com.gpay.payment.client.AuditClient;
import com.gpay.payment.client.WalletClient;
import com.gpay.payment.constant.TransactionStatus;
import com.gpay.payment.constant.TransactionType;
import com.gpay.payment.dto.PaymentDTO;
import com.gpay.payment.dto.PaymentDTO.TransactionResponse;
import com.gpay.payment.entity.TransferRequest;
import com.gpay.payment.entity.Transactions;
import com.gpay.payment.exception.PaymentException;
import com.gpay.payment.repository.TransactionRepository;
import com.gpay.payment.repository.TransferRequestRepository;
import com.gpay.payment.service.NotificationOutboxService;
import com.gpay.payment.service.RateLimitService;
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
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferServiceImplTest {
    private static final UUID FROM = UUID.randomUUID();
    private static final UUID TO = UUID.randomUUID();
    private static final UUID TXN_ID = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("10000.00");
    private static final String KEY = "idem-1";

    @Mock TransactionRepository transactionRepository;
    @Mock TransferRequestRepository transferRequestRepository;
    @Mock WalletClient walletClient;
    @Mock AuditClient auditClient;
    @Mock RateLimitService rateLimitService;
    @Mock NotificationOutboxService notificationOutbox;
    @Mock PlatformTransactionManager transactionManager;   // TransactionTemplate runs the callback inline

    TransferServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TransferServiceImpl(transactionRepository, transferRequestRepository, walletClient,
                auditClient, rateLimitService, notificationOutbox, transactionManager);
    }

    private static PaymentDTO.TransferRequest request() {
        return new PaymentDTO.TransferRequest(TO, AMOUNT, "Lunch");
    }

    private static Transactions stored(TransactionStatus status, String reason) {
        return Transactions.builder().id(TXN_ID).userId(FROM).type(TransactionType.TRANSFER).status(status)
                .amount(AMOUNT).failureReason(reason).createdAt(LocalDateTime.now()).build();
    }

    /** Happy path up to the wallet call: reservation OK, PENDING rows committed. */
    private void stubPendingCreated() {
        when(transactionRepository.findByUserIdAndIdempotencyKey(FROM, KEY)).thenReturn(Optional.empty());
        when(rateLimitService.tryReserveDailyTransfer(eq(FROM), eq(AMOUNT), any())).thenReturn(true);
        when(transactionRepository.save(any())).thenAnswer(inv -> {
            Transactions t = inv.getArgument(0);
            t.setId(TXN_ID);
            t.setCreatedAt(LocalDateTime.now());
            return t;
        });
    }

    @Test
    void successMarksSuccessAndRecordsOutboxEventAtomically() {
        stubPendingCreated();
        when(transactionRepository.resolvePending(eq(TXN_ID), eq(TransactionStatus.SUCCESS), isNull(), any())).thenReturn(1);
        when(transactionRepository.findById(TXN_ID)).thenReturn(Optional.of(stored(TransactionStatus.SUCCESS, null)));

        TransactionResponse res = service.transfer(FROM, request(), KEY);

        assertThat(res.status()).isEqualTo(TransactionStatus.SUCCESS);
        verify(walletClient).atomicTransfer(FROM, TO, AMOUNT, TXN_ID.toString(), "Lunch");
        verify(notificationOutbox).transferSucceeded(argThat(t -> t.getId().equals(TXN_ID)), eq(TO));
        verify(rateLimitService, never()).releaseDailyTransfer(any(), any(), any());
    }

    @Test
    void lostCompareAndSetDoesNotEmitSecondNotification() {
        stubPendingCreated();
        // Reconciler already resolved it: our CAS updates 0 rows
        when(transactionRepository.resolvePending(eq(TXN_ID), eq(TransactionStatus.SUCCESS), isNull(), any())).thenReturn(0);
        when(transactionRepository.findById(TXN_ID)).thenReturn(Optional.of(stored(TransactionStatus.SUCCESS, null)));

        service.transfer(FROM, request(), KEY);

        verifyNoInteractions(notificationOutbox);
    }

    @Test
    void insufficientBalanceMarksFailedAndReleasesDailyReservation() {
        stubPendingCreated();
        doThrow(HttpClientErrorException.create(HttpStatus.UNPROCESSABLE_ENTITY, "422", null, null, null))
                .when(walletClient).atomicTransfer(any(), any(), any(), any(), any());
        when(transactionRepository.resolvePending(eq(TXN_ID), eq(TransactionStatus.FAILED), eq("Insufficient balance"), any()))
                .thenReturn(1);
        when(transactionRepository.findById(TXN_ID))
                .thenReturn(Optional.of(stored(TransactionStatus.FAILED, "Insufficient balance")));

        TransactionResponse res = service.transfer(FROM, request(), KEY);

        assertThat(res.status()).isEqualTo(TransactionStatus.FAILED);
        assertThat(res.failureReason()).isEqualTo("Insufficient balance");
        verify(rateLimitService).releaseDailyTransfer(eq(FROM), eq(AMOUNT), any());
        verifyNoInteractions(notificationOutbox);
    }

    @Test
    void unknownOutcomeRetriesOnceThenStaysPendingNeverFailed() {
        stubPendingCreated();
        doThrow(new ResourceAccessException("read timeout"))
                .when(walletClient).atomicTransfer(any(), any(), any(), any(), any());
        when(transactionRepository.findById(TXN_ID)).thenReturn(Optional.of(stored(TransactionStatus.PENDING, null)));

        TransactionResponse res = service.transfer(FROM, request(), KEY);

        assertThat(res.status()).isEqualTo(TransactionStatus.PENDING);
        verify(walletClient, times(2)).atomicTransfer(any(), any(), any(), any(), any());
        verify(transactionRepository, never()).resolvePending(any(), any(), any(), any());
        verify(rateLimitService, never()).releaseDailyTransfer(any(), any(), any());
    }

    @Test
    void dailyLimitExceededCreatesNothing() {
        when(transactionRepository.findByUserIdAndIdempotencyKey(FROM, KEY)).thenReturn(Optional.empty());
        when(rateLimitService.tryReserveDailyTransfer(eq(FROM), eq(AMOUNT), any())).thenReturn(false);
        when(rateLimitService.getRemainingDailyLimit(eq(FROM), any())).thenReturn(new BigDecimal("5000.00"));

        assertThatThrownBy(() -> service.transfer(FROM, request(), KEY))
                .isInstanceOf(PaymentException.DailyLimitExceededException.class)
                .hasMessageContaining("5000.00");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void selfTransferIsRejectedBeforeReservingLimit() {
        when(transactionRepository.findByUserIdAndIdempotencyKey(FROM, KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transfer(FROM, new PaymentDTO.TransferRequest(FROM, AMOUNT, null), KEY))
                .isInstanceOf(PaymentException.InvalidTransferException.class);
        verifyNoInteractions(rateLimitService);
    }

    @Test
    void repeatedKeyReplaysWithoutCallingWallet() {
        when(transactionRepository.findByUserIdAndIdempotencyKey(FROM, KEY))
                .thenReturn(Optional.of(stored(TransactionStatus.SUCCESS, null)));

        TransactionResponse res = service.transfer(FROM, request(), KEY);

        assertThat(res.transactionId()).isEqualTo(TXN_ID);
        verifyNoInteractions(walletClient, rateLimitService);
    }

    @Test
    void reconcileResendsWithOriginalRecipient() {
        Transactions pending = stored(TransactionStatus.PENDING, null);
        when(transferRequestRepository.findByTransactionId(TXN_ID))
                .thenReturn(Optional.of(TransferRequest.builder().toUserId(TO).build()));
        when(transactionRepository.resolvePending(eq(TXN_ID), eq(TransactionStatus.SUCCESS), isNull(), any())).thenReturn(1);
        when(transactionRepository.findById(TXN_ID)).thenReturn(Optional.of(stored(TransactionStatus.SUCCESS, null)));

        service.reconcile(pending);

        verify(walletClient).atomicTransfer(FROM, TO, AMOUNT, TXN_ID.toString(), null);
        verify(notificationOutbox).transferSucceeded(pending, TO);
    }
}
