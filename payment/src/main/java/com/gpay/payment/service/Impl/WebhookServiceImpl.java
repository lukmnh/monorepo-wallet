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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookServiceImpl implements WebhookService {
    private final TransactionRepository transactionRepository;
    private final TopUpRequestRepository topupRequestRepository;
    private final WalletClient walletClient;
    private final AuditClient auditClient;

    @Value("${payment.mock-gateway-secret}")
    private String gatewaySecret;

    @Override
    @Transactional
    public void processWebhook(WebhookPayload payload, String signature) {
        long start = System.currentTimeMillis();

        validateSignature(payload, signature);

        TopupRequest topupReq = topupRequestRepository.findByGatewayRef(payload.gatewayRef())
                .orElseThrow(() -> new PaymentException.WebhookException("Unknown gatewayRef: " + payload.gatewayRef()));

        Transactions txn = topupReq.getTransaction();

        // if already resolved, skip
        if (txn.getStatus() != TransactionStatus.PENDING) {
            log.info("Webhook for already-resolved txnId={} status={}, skipping",
                    txn.getId(), txn.getStatus());
            return;
        }

        if ("SUCCESS".equals(payload.status())) {
            walletClient.credit(
                    txn.getUserId(),
                    txn.getAmount(),
                    txn.getId().toString(),
                    "Top-up via payment gateway"
            );
            txn.setStatus(TransactionStatus.SUCCESS);
            log.info("Topup SUCCESS txnId={} userId={} amount={}", txn.getId(), txn.getUserId(), txn.getAmount());
        } else {
            txn.setStatus(TransactionStatus.FAILED);
            log.info("Topup FAILED txnId={} userId={}", txn.getId(), txn.getUserId());
        }

        topupReq.setCallbackStatus(payload.status());
        topupReq.setWebhookReceivedAt(LocalDateTime.now());
        topupRequestRepository.save(topupReq);
        transactionRepository.save(txn);

        long duration = System.currentTimeMillis() - start;
        auditClient.log(txn.getUserId(), txn.getId(), "TOPUP_WEBHOOK",
                txn.getStatus().name(), payload, null, duration, null);
    }


    private void validateSignature(WebhookPayload payload, String signature) {
        try {
            String data = payload.gatewayRef() + ":" + payload.status() + ":" + payload.amount().toPlainString();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(gatewaySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));

            if (!expected.equals(signature)) {
                log.warn("Invalid webhook signature. Expected={} Got={}", expected, signature);
                throw new PaymentException.WebhookSignatureException("Invalid webhook signature");
            }
        } catch (PaymentException.WebhookSignatureException e) {
            throw e;
        } catch (Exception e) {
            throw new PaymentException.WebhookException("Signature validation error: " + e.getMessage());
        }
    }
}
