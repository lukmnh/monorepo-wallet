package com.gpay.payment.scheduler;

import com.gpay.payment.entity.Transactions;
import com.gpay.payment.repository.TransactionRepository;
import com.gpay.payment.service.TransferService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
public class PendingTransactionScheduler {
    private final TransactionRepository transactionRepository;
    private final TransferService transferService;
    private final int pendingExpireMinutes;
    private final int transferReconcileAfterSeconds;

    public PendingTransactionScheduler(TransactionRepository transactionRepository,
                                       TransferService transferService,
                                       @Value("${payment.pending-expire-minutes}") int pendingExpireMinutes,
                                       @Value("${payment.transfer-reconcile-after-seconds}") int transferReconcileAfterSeconds) {
        this.transactionRepository = transactionRepository;
        this.transferService = transferService;
        this.pendingExpireMinutes = pendingExpireMinutes;
        this.transferReconcileAfterSeconds = transferReconcileAfterSeconds;
    }

    /**
     * Single conditional UPDATE (status = PENDING): rows a webhook holds locked or already resolved
     * are skipped, so a credited top-up can never be overwritten to EXPIRED.
     */
    @Scheduled(fixedDelayString = "${payment.scheduler-interval-ms}")
    public void expireStaleTopups() {
        LocalDateTime now = LocalDateTime.now();
        int expired = transactionRepository.expirePendingTopups(now.minusMinutes(pendingExpireMinutes),
                "No gateway callback within " + pendingExpireMinutes + " minutes", now);
        if (expired > 0) log.info("Expired {} stale PENDING top-ups", expired);
    }

    /**
     * Transfers stay PENDING only when wallet-service's answer was lost (timeout/5xx).
     * The grace period keeps this away from requests that are still in flight.
     */
    @Scheduled(fixedDelayString = "${payment.scheduler-interval-ms}")
    public void reconcilePendingTransfers() {
        List<Transactions> pending = transactionRepository.findPendingTransfersCreatedBefore(
                LocalDateTime.now().minusSeconds(transferReconcileAfterSeconds));
        for (Transactions txn : pending) {
            MDC.put("traceId", txn.getTraceId());
            MDC.put("userId", String.valueOf(txn.getUserId()));
            try {
                transferService.reconcile(txn);
            } catch (RuntimeException e) {
                log.error("Reconciliation failed txnId={}, will retry next run: {}", txn.getId(), e.getMessage());
            } finally {
                MDC.clear();
            }
        }
    }
}
