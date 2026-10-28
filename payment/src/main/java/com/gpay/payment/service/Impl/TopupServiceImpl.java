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
import com.gpay.payment.service.TopupService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class TopupServiceImpl implements TopupService {

    private final TransactionRepository transactionRepository;
    private final TopUpRequestRepository topupRequestRepository;
    private final PaymentGatewayClient gatewayClient;
    private final AuditClient auditClient;
    private final TransactionTemplate transactionTemplate;
    private final int pendingExpireMinutes;

    public TopupServiceImpl(TransactionRepository transactionRepository,
                            TopUpRequestRepository topupRequestRepository,
                            PaymentGatewayClient gatewayClient,
                            AuditClient auditClient,
                            PlatformTransactionManager transactionManager,
                            @Value("${payment.pending-expire-minutes}") int pendingExpireMinutes) {
        this.transactionRepository = transactionRepository;
        this.topupRequestRepository = topupRequestRepository;
        this.gatewayClient = gatewayClient;
        this.auditClient = auditClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.pendingExpireMinutes = pendingExpireMinutes;
    }

    /**
     * Not @Transactional on purpose: the PENDING rows are committed BEFORE the gateway is called,
     * so a webhook can never arrive for a transaction that is not yet visible. No DB connection is
     * held while waiting on the gateway.
     */
    @Override
    public TransactionResponse topup(UUID userId, PaymentDTO.TopupRequest request, String idempotencyKey) {
        long start = System.currentTimeMillis();

        Optional<Transactions> existing = transactionRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            return IdempotentReplay.replay(existing.get(), TransactionType.TOPUP, request.amount());
        }

        TopupRequest topupReq;
        try {
            topupReq = transactionTemplate.execute(status -> createPending(userId, request, idempotencyKey));
        } catch (DataIntegrityViolationException e) {
            // Lost a race on UNIQUE (user_id, idempotency_key) against a concurrent duplicate: replay the winner
            return transactionRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                    .map(t -> IdempotentReplay.replay(t, TransactionType.TOPUP, request.amount()))
                    .orElseThrow(() -> e);
        }
        UUID txnId = topupReq.getTransaction().getId();

        try {
            Map<String, Object> gatewayResponse = gatewayClient.requestTopup(txnId, request.amount(), request.scenario());
            String gatewayRef = gatewayResponse != null ? (String) gatewayResponse.get("gatewayRef") : null;
            if (gatewayRef != null) {
                // Conditional: the webhook may have stored it first (it can also match on transactionId)
                topupRequestRepository.assignGatewayRefIfAbsent(topupReq.getId(), gatewayRef);
            }
            log.info("Gateway accepted topup txnId={} gatewayRef={}", txnId, gatewayRef);
        } catch (HttpClientErrorException e) {
            // 4xx: the gateway definitively refused, no payment will happen
            transactionRepository.resolvePending(txnId, TransactionStatus.FAILED,
                    "Gateway rejected request: HTTP " + e.getStatusCode().value(), LocalDateTime.now());
            log.warn("Gateway rejected topup txnId={}: {}", txnId, e.getStatusCode());
        } catch (RestClientException e) {
            // Timeout / 5xx: outcome unknown. Stays PENDING until a webhook arrives or the expiry job runs
            log.warn("Gateway call failed for txnId={}, stays PENDING: {}", txnId, e.getMessage());
        }

        Transactions current = transactionRepository.findById(txnId).orElseThrow();
        auditClient.log(userId, txnId, "TOPUP_INITIATED", current.getStatus().name(),
                Map.of("amount", request.amount(), "scenario", request.scenario()), null,
                System.currentTimeMillis() - start, null);

        return TransactionResponse.from(current);
    }

    private TopupRequest createPending(UUID userId, PaymentDTO.TopupRequest request, String idempotencyKey) {
        Transactions txn = transactionRepository.save(Transactions.builder()
                .idempotencyKey(idempotencyKey)
                .userId(userId)
                .type(TransactionType.TOPUP)
                .status(TransactionStatus.PENDING)
                .amount(request.amount())
                .description(request.description() != null ? request.description() : "Top up via payment gateway")
                .traceId(MDC.get("traceId"))
                .build());
        return topupRequestRepository.save(TopupRequest.builder()
                .transaction(txn)
                .expiresAt(LocalDateTime.now().plusMinutes(pendingExpireMinutes))
                .build());
    }
}
