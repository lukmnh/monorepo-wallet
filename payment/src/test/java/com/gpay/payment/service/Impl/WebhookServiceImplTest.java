package com.gpay.payment.service.Impl;

import com.gpay.payment.client.AuditClient;
import com.gpay.payment.client.WalletClient;
import com.gpay.payment.constant.TransactionStatus;
import com.gpay.payment.constant.TransactionType;
import com.gpay.payment.dto.PaymentDTO.WebhookPayload;
import com.gpay.payment.entity.TopupRequest;
import com.gpay.payment.entity.Transactions;
import com.gpay.payment.exception.PaymentException;
import com.gpay.payment.repository.TopUpRequestRepository;
import com.gpay.payment.repository.TransactionRepository;
import com.gpay.payment.service.NotificationOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookServiceImplTest {
    private static final String SECRET = "gw-secret";
    private static final String REF = "GW-ABCD1234";
    private static final UUID TXN_ID = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("50000.00");

    @Mock TransactionRepository transactionRepository;
    @Mock TopUpRequestRepository topupRequestRepository;
    @Mock WalletClient walletClient;
    @Mock AuditClient auditClient;
    @Mock NotificationOutboxService notificationOutbox;

    WebhookServiceImpl service;
    Transactions txn;

    @BeforeEach
    void setUp() {
        service = new WebhookServiceImpl(transactionRepository, topupRequestRepository, walletClient,
                auditClient, notificationOutbox, SECRET);
        txn = Transactions.builder().id(TXN_ID).userId(USER).type(TransactionType.TOPUP)
                .status(TransactionStatus.PENDING).amount(AMOUNT).build();
    }

    private void stubKnownTopup() {
        when(topupRequestRepository.findByGatewayRef(REF))
                .thenReturn(Optional.of(TopupRequest.builder().id(UUID.randomUUID()).transaction(txn).gatewayRef(REF).build()));
        when(transactionRepository.findByIdForUpdate(TXN_ID)).thenReturn(Optional.of(txn));
    }

    private static String sign(WebhookPayload p) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String data = p.gatewayRef() + ":" + p.transactionId() + ":" + p.status() + ":" + p.amount().toPlainString();
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static WebhookPayload payload(String status, BigDecimal amount) {
        return new WebhookPayload(REF, TXN_ID, status, amount);
    }

    @Test
    void successCreditsWalletMarksSuccessAndRecordsNotification() throws Exception {
        stubKnownTopup();
        WebhookPayload p = payload("SUCCESS", AMOUNT);

        service.processWebhook(p, sign(p));

        verify(walletClient).credit(USER, AMOUNT, TXN_ID.toString(), "Top-up via payment gateway");
        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        verify(notificationOutbox).topupSucceeded(txn);
    }

    @Test
    void lateSuccessForExpiredTopupStillCredits() throws Exception {
        txn.setStatus(TransactionStatus.EXPIRED);
        txn.setFailureReason("No gateway callback within 60 minutes");
        stubKnownTopup();
        WebhookPayload p = payload("SUCCESS", AMOUNT);

        service.processWebhook(p, sign(p));

        verify(walletClient).credit(any(), any(), any(), any());
        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(txn.getFailureReason()).isNull();
    }

    @Test
    void failedMarksFailedWithoutCreditOrNotification() throws Exception {
        stubKnownTopup();
        WebhookPayload p = payload("FAILED", AMOUNT);

        service.processWebhook(p, sign(p));

        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.FAILED);
        verifyNoInteractions(walletClient, notificationOutbox);
    }

    @Test
    void duplicateDeliveryForResolvedTopupIsNoOp() throws Exception {
        txn.setStatus(TransactionStatus.SUCCESS);
        stubKnownTopup();
        WebhookPayload p = payload("SUCCESS", AMOUNT);

        service.processWebhook(p, sign(p));

        verifyNoInteractions(walletClient, notificationOutbox);
    }

    @Test
    void invalidSignatureIsRejectedBeforeAnyLookup() {
        assertThatThrownBy(() -> service.processWebhook(payload("SUCCESS", AMOUNT), "deadbeef"))
                .isInstanceOf(PaymentException.WebhookSignatureException.class);
        verifyNoInteractions(topupRequestRepository, walletClient);
    }

    @Test
    void amountMismatchIsRejected() throws Exception {
        stubKnownTopup();
        WebhookPayload p = payload("SUCCESS", new BigDecimal("99999.00"));   // validly signed, wrong amount

        assertThatThrownBy(() -> service.processWebhook(p, sign(p)))
                .isInstanceOf(PaymentException.WebhookException.class);
        verifyNoInteractions(walletClient);
    }

    @Test
    void fallsBackToTransactionIdWhenGatewayRefNotStoredYet() throws Exception {
        when(topupRequestRepository.findByGatewayRef(REF)).thenReturn(Optional.empty());
        when(topupRequestRepository.findByTransactionId(TXN_ID))
                .thenReturn(Optional.of(TopupRequest.builder().id(UUID.randomUUID()).transaction(txn).build()));
        when(transactionRepository.findByIdForUpdate(TXN_ID)).thenReturn(Optional.of(txn));
        WebhookPayload p = payload("SUCCESS", AMOUNT);

        service.processWebhook(p, sign(p));

        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
    }
}
