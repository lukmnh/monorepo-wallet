package com.gpay.payment.service.Impl;

import com.gpay.payment.client.AuditClient;
import com.gpay.payment.client.WalletClient;
import com.gpay.payment.constant.TransactionStatus;
import com.gpay.payment.dto.PaymentDTO.WebhookPayload;
import com.gpay.payment.entity.TopupRequest;
import com.gpay.payment.entity.Transactions;
import com.gpay.payment.exception.PaymentException;
import com.gpay.payment.repository.TopUpRequestRepository;
import com.gpay.payment.repository.TransactionRepository;
import com.gpay.payment.service.WebhookService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

@Service
@Slf4j
public class WebhookServiceImpl implements WebhookService {
    private final TransactionRepository transactionRepository;
    private final TopUpRequestRepository topupRequestRepository;
    private final WalletClient walletClient;
    private final AuditClient auditClient;
    private final byte[] gatewaySecret;

    public WebhookServiceImpl(TransactionRepository transactionRepository,
                              TopUpRequestRepository topupRequestRepository,
                              WalletClient walletClient,
                              AuditClient auditClient,
                              @Value("${payment.mock-gateway-secret}") String gatewaySecret) {
        this.transactionRepository = transactionRepository;
        this.topupRequestRepository = topupRequestRepository;
        this.walletClient = walletClient;
        this.auditClient = auditClient;
        // An empty HMAC key would make signatures forgeable by anyone
        if (gatewaySecret == null || gatewaySecret.isBlank()) {
            throw new IllegalStateException("MOCK_GATEWAY_SECRET must be set");
        }
        this.gatewaySecret = gatewaySecret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    @Transactional
    public void processWebhook(WebhookPayload payload, String signature) {
        long start = System.currentTimeMillis();

        validateSignature(payload, signature);

        // gateway_ref may not be stored yet if the webhook beat the initiate response: fall back to our own id
        TopupRequest topupReq = topupRequestRepository.findByGatewayRef(payload.gatewayRef())
                .or(() -> topupRequestRepository.findByTransactionId(payload.transactionId()))
                .orElseThrow(() -> new PaymentException.WebhookException("Unknown top-up: " + payload.gatewayRef()));

        UUID txnId = topupReq.getTransaction().getId();
        if (!txnId.equals(payload.transactionId())
                || (topupReq.getGatewayRef() != null && !topupReq.getGatewayRef().equals(payload.gatewayRef()))) {
            throw new PaymentException.WebhookException("gatewayRef/transactionId mismatch");
        }

        // Row lock: duplicate deliveries and the expiry job are serialized on this transaction
        Transactions txn = transactionRepository.findByIdForUpdate(txnId).orElseThrow();

        if (txn.getAmount().compareTo(payload.amount()) != 0) {
            log.warn("Webhook amount mismatch txnId={} expected={} got={}", txnId, txn.getAmount(), payload.amount());
            throw new PaymentException.WebhookException("Amount mismatch");
        }

        if (txn.getStatus() == TransactionStatus.SUCCESS || txn.getStatus() == TransactionStatus.FAILED) {
            log.info("Webhook for already-resolved txnId={} status={}, skipping", txnId, txn.getStatus());
            return;
        }

        if ("SUCCESS".equals(payload.status())) {
            // The gateway took the money: credit even if we already EXPIRED it (late callback).
            // Idempotent on wallet-service by referenceId, so redelivery cannot double-credit.
            walletClient.credit(txn.getUserId(), txn.getAmount(), txnId.toString(), "Top-up via payment gateway");
            if (txn.getStatus() == TransactionStatus.EXPIRED) {
                log.warn("Late SUCCESS webhook for EXPIRED txnId={}, crediting", txnId);
            }
            txn.setStatus(TransactionStatus.SUCCESS);
            txn.setFailureReason(null);
            log.info("Topup SUCCESS txnId={} userId={} amount={}", txnId, txn.getUserId(), txn.getAmount());
        } else if (txn.getStatus() == TransactionStatus.PENDING) {
            txn.setStatus(TransactionStatus.FAILED);
            txn.setFailureReason("Gateway reported status " + payload.status());
            log.info("Topup FAILED txnId={} userId={}", txnId, txn.getUserId());
        }

        topupReq.setGatewayRef(payload.gatewayRef());
        topupReq.setCallbackStatus(payload.status());
        topupReq.setWebhookReceivedAt(LocalDateTime.now());
        topupRequestRepository.save(topupReq);
        transactionRepository.save(txn);

        auditClient.log(txn.getUserId(), txnId, "TOPUP_WEBHOOK", txn.getStatus().name(), payload, null,
                System.currentTimeMillis() - start, null);
    }

    // Signed fields: gatewayRef:transactionId:status:amount (must match the gateway's WebhookDispatcher)
    private void validateSignature(WebhookPayload payload, String signature) {
        String data = payload.gatewayRef() + ":" + payload.transactionId() + ":" + payload.status() + ":"
                + payload.amount().toPlainString();
        byte[] expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(gatewaySecret, "HmacSHA256"));
            expected = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }

        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signature);
        } catch (IllegalArgumentException e) {
            provided = new byte[0];
        }

        // Constant-time compare; never log the expected value (it is a valid signature for this payload)
        if (!MessageDigest.isEqual(expected, provided)) {
            log.warn("Invalid webhook signature gatewayRef={}", payload.gatewayRef());
            throw new PaymentException.WebhookSignatureException("Invalid webhook signature");
        }
    }
}
