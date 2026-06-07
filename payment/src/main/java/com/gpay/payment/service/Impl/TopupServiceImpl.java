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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TopupServiceImpl implements TopupService {

    private final TransactionRepository transactionRepository;
    private final TopUpRequestRepository topupRequestRepository;
    private final PaymentGatewayClient gatewayClient;
    private final AuditClient auditClient;

    @Value("${payment.pending-expire-minutes:60}")
    private int pendingExpireMinutes;

    @Override
    @Transactional
    public TransactionResponse topup(UUID userId, PaymentDTO.TopupRequest request, String idempotencyKey) {
        long start = System.currentTimeMillis();

        Transactions txn = Transactions.builder()
                .idempotencyKey(idempotencyKey)
                .userId(userId)
                .type(TransactionType.TOPUP)
                .status(TransactionStatus.PENDING)
                .amount(request.amount())
                .description(request.description() != null ? request.description() : "Top up via payment gateway")
                .traceId(MDC.get("traceId"))
                .build();
        txn = transactionRepository.save(txn);

        TopupRequest topupReq = TopupRequest.builder()
                .transaction(txn)
                .expiresAt(LocalDateTime.now().plusMinutes(pendingExpireMinutes))
                .build();
        topupRequestRepository.save(topupReq);

        try {
            var gatewayResponse = gatewayClient.requestTopup(txn.getId(), request.amount(), request.scenario());
            String gatewayRef = (String) gatewayResponse.get("gatewayRef");
            topupReq.setGatewayRef(gatewayRef);
            topupRequestRepository.save(topupReq);
            log.info("Gateway accepted topup txnId={} gatewayRef={}", txn.getId(), gatewayRef);
        } catch (ResourceAccessException e) {
            log.warn("Gateway timeout for txnId={}, transaction stays PENDING", txn.getId());
        } catch (Exception e) {
            log.error("Gateway error for txnId={}: {}", txn.getId(), e.getMessage());
        }

        long duration = System.currentTimeMillis() - start;
        auditClient.log(userId, txn.getId(), "TOPUP_INITIATED", "PENDING",
                Map.of("amount", request.amount()), null, duration, null);

        return toResponse(txn);
    }

    private TransactionResponse toResponse(Transactions txn) {
        return new TransactionResponse(txn.getId(), txn.getType(), txn.getStatus(),
                txn.getAmount(), txn.getDescription(), txn.getCreatedAt());
    }

    private static class Map {
        static java.util.Map<String, Object> of(String k, Object v) {
            return java.util.Map.of(k, v);
        }
    }
}
