package com.gpay.payment.scheduler;

import com.gpay.payment.constant.TransactionStatus;
import com.gpay.payment.entity.Transactions;
import com.gpay.payment.repository.TransactionRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PendingTransactionScheduler {
    private final TransactionRepository transactionRepository;

    @Value("${payment.pending-expire-minutes:60}")
    private int pendingExpireMinutes;

    @Scheduled(fixedDelay = 300_000)
    @Transactional
    public void expireStaleTransactions() {
        LocalDateTime expiresBefore = LocalDateTime.now().minusMinutes(pendingExpireMinutes);
        List<Transactions> stale = transactionRepository.findExpiredPendingTopups(expiresBefore);

        if (stale.isEmpty()) return;

        log.info("Expiring {} stale PENDING topup transactions", stale.size());
        stale.forEach(txn -> {
            txn.setStatus(TransactionStatus.EXPIRED);
            log.info("Expired txnId={} userId={} amount={} createdAt={}",
                    txn.getId(), txn.getUserId(), txn.getAmount(), txn.getCreatedAt());
        });
        transactionRepository.saveAll(stale);
    }
}
