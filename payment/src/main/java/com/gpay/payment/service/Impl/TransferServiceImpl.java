package com.gpay.payment.service.Impl;

import com.gpay.payment.client.AuditClient;
import com.gpay.payment.client.WalletClient;
import com.gpay.payment.constant.TransactionStatus;
import com.gpay.payment.constant.TransactionType;
import com.gpay.payment.dto.PaymentDTO;
import com.gpay.payment.dto.PaymentDTO.TransactionResponse;
import com.gpay.payment.entity.Transactions;
import com.gpay.payment.entity.TransferRequest;
import com.gpay.payment.exception.PaymentException;
import com.gpay.payment.repository.TransactionRepository;
import com.gpay.payment.repository.TransferRequestRepository;
import com.gpay.payment.service.NotificationOutboxService;
import com.gpay.payment.service.RateLimitService;
import com.gpay.payment.service.TransferService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Transfer lifecycle:
 * 1. commit PENDING (+ daily-limit reservation)  2. call wallet-service  3. record the outcome.
 * Definitive failures are persisted as FAILED with a reason. An unknown outcome (timeout/5xx) stays
 * PENDING and is re-sent by the reconciler, which is safe because wallet-service dedupes by referenceId.
 */
@Service
@Slf4j
public class TransferServiceImpl implements TransferService {
    private static final int WALLET_ATTEMPTS = 2;

    private final TransactionRepository transactionRepository;
    private final TransferRequestRepository transferRequestRepository;
    private final WalletClient walletClient;
    private final AuditClient auditClient;
    private final RateLimitService rateLimitService;
    private final NotificationOutboxService notificationOutbox;
    private final TransactionTemplate transactionTemplate;

    public TransferServiceImpl(TransactionRepository transactionRepository,
                               TransferRequestRepository transferRequestRepository,
                               WalletClient walletClient,
                               AuditClient auditClient,
                               RateLimitService rateLimitService,
                               NotificationOutboxService notificationOutbox,
                               PlatformTransactionManager transactionManager) {
        this.transactionRepository = transactionRepository;
        this.transferRequestRepository = transferRequestRepository;
        this.walletClient = walletClient;
        this.auditClient = auditClient;
        this.rateLimitService = rateLimitService;
        this.notificationOutbox = notificationOutbox;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public TransactionResponse transfer(UUID fromUserId, PaymentDTO.TransferRequest request, String idempotencyKey) {
        long start = System.currentTimeMillis();

        Optional<Transactions> existing = transactionRepository.findByUserIdAndIdempotencyKey(fromUserId, idempotencyKey);
        if (existing.isPresent()) {
            return IdempotentReplay.replay(existing.get(), TransactionType.TRANSFER, request.amount());
        }

        if (fromUserId.equals(request.toUserId())) {
            throw new PaymentException.InvalidTransferException("Cannot transfer to yourself");
        }

        LocalDate today = LocalDate.now();
        if (!rateLimitService.tryReserveDailyTransfer(fromUserId, request.amount(), today)) {
            throw new PaymentException.DailyLimitExceededException("Daily transfer limit exceeded. Remaining: "
                    + rateLimitService.getRemainingDailyLimit(fromUserId, today));
        }

        Transactions txn;
        try {
            txn = transactionTemplate.execute(status -> createPending(fromUserId, request, idempotencyKey));
        } catch (DataIntegrityViolationException e) {
            // Lost a race on UNIQUE (user_id, idempotency_key) against a concurrent duplicate: replay the winner
            rateLimitService.releaseDailyTransfer(fromUserId, request.amount(), today);
            return transactionRepository.findByUserIdAndIdempotencyKey(fromUserId, idempotencyKey)
                    .map(t -> IdempotentReplay.replay(t, TransactionType.TRANSFER, request.amount()))
                    .orElseThrow(() -> e);
        } catch (RuntimeException e) {
            rateLimitService.releaseDailyTransfer(fromUserId, request.amount(), today);
            throw e;
        }

        Transactions result = settle(txn, request.toUserId());
        auditClient.log(fromUserId, txn.getId(), "TRANSFER", result.getStatus().name(),
                Map.of("toUserId", request.toUserId(), "amount", request.amount()),
                result.getFailureReason() != null ? Map.of("failureReason", result.getFailureReason()) : null,
                System.currentTimeMillis() - start, null);
        return TransactionResponse.from(result);
    }

    @Override
    public void reconcile(Transactions pendingTransfer) {
        TransferRequest transferReq = transferRequestRepository.findByTransactionId(pendingTransfer.getId())
                .orElseThrow(() -> new IllegalStateException("No transfer_request for txnId=" + pendingTransfer.getId()));
        Transactions result = settle(pendingTransfer, transferReq.getToUserId());
        log.info("Reconciled transfer txnId={} status={}", result.getId(), result.getStatus());
        auditClient.log(result.getUserId(), result.getId(), "TRANSFER_RECONCILED", result.getStatus().name(),
                null, null, 0, null);
    }

    private Transactions createPending(UUID fromUserId, PaymentDTO.TransferRequest request, String idempotencyKey) {
        Transactions txn = transactionRepository.save(Transactions.builder()
                .idempotencyKey(idempotencyKey)
                .userId(fromUserId)
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.PENDING)
                .amount(request.amount())
                .description(request.description() != null ? request.description() : "Transfer")
                .traceId(MDC.get("traceId"))
                .build());
        transferRequestRepository.save(TransferRequest.builder()
                .transaction(txn)
                .fromUserId(fromUserId)
                .toUserId(request.toUserId())
                .build());
        return txn;
    }

    /** Calls wallet-service and records the outcome; returns the transaction's current state. */
    private Transactions settle(Transactions txn, UUID toUserId) {
        try {
            callWallet(txn, toUserId);
            markSucceeded(txn, toUserId);
            log.info("Transfer SUCCESS txnId={} from={} to={} amount={}", txn.getId(), txn.getUserId(), toUserId, txn.getAmount());
        } catch (HttpClientErrorException.UnprocessableEntity e) {
            fail(txn, "Insufficient balance");
        } catch (HttpClientErrorException.NotFound e) {
            fail(txn, "Wallet not found for sender or recipient");
        } catch (HttpClientErrorException e) {
            fail(txn, "Rejected by wallet-service: HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            // Timeout / 5xx after retries: the transfer MAY have been applied. Never mark FAILED here.
            log.warn("Transfer outcome unknown txnId={}, left PENDING for reconciliation: {}", txn.getId(), e.getMessage());
        }
        return transactionRepository.findById(txn.getId()).orElseThrow();
    }

    private void callWallet(Transactions txn, UUID toUserId) {
        for (int attempt = 1; ; attempt++) {
            try {
                // referenceId = txnId makes the call idempotent on wallet-service, so retries are safe
                walletClient.atomicTransfer(txn.getUserId(), toUserId, txn.getAmount(),
                        txn.getId().toString(), txn.getDescription());
                return;
            } catch (HttpClientErrorException e) {
                throw e;   // 4xx is a definitive answer
            } catch (RestClientException e) {
                if (attempt >= WALLET_ATTEMPTS) throw e;
                log.warn("wallet-service call failed txnId={} attempt={}, retrying: {}", txn.getId(), attempt, e.getMessage());
            }
        }
    }

    /**
     * SUCCESS + outbox event in one DB transaction. Only the actor that wins the PENDING -> SUCCESS
     * compare-and-set records the event, so request thread and reconciler never notify twice.
     */
    private void markSucceeded(Transactions txn, UUID toUserId) {
        transactionTemplate.executeWithoutResult(status -> {
            if (transactionRepository.resolvePending(txn.getId(), TransactionStatus.SUCCESS, null, LocalDateTime.now()) == 1) {
                notificationOutbox.transferSucceeded(txn, toUserId);
            }
        });
    }

    private void fail(Transactions txn, String reason) {
        // Release the daily-limit reservation only if WE moved it out of PENDING (no double release)
        if (transactionRepository.resolvePending(txn.getId(), TransactionStatus.FAILED, reason, LocalDateTime.now()) == 1) {
            rateLimitService.releaseDailyTransfer(txn.getUserId(), txn.getAmount(), txn.getCreatedAt().toLocalDate());
        }
        log.warn("Transfer FAILED txnId={}: {}", txn.getId(), reason);
    }
}
