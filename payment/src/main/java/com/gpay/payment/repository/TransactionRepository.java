package com.gpay.payment.repository;

import com.gpay.payment.constant.TransactionStatus;
import com.gpay.payment.entity.Transactions;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transactions, UUID> {

    /** Idempotency replay source of truth (UNIQUE (user_id, idempotency_key)). */
    Optional<Transactions> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    /** Serializes webhook deliveries and the expiry job on the same transaction row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transactions t WHERE t.id = :id")
    Optional<Transactions> findByIdForUpdate(UUID id);

    /**
     * Compare-and-set out of PENDING. Returns 0 if another actor (webhook, scheduler, reconciler)
     * already resolved the transaction, so a terminal status is never overwritten.
     */
    @Transactional
    @Modifying
    @Query("""
           UPDATE Transactions t SET t.status = :status, t.failureReason = :reason, t.updatedAt = :now
           WHERE t.id = :id AND t.status = com.gpay.payment.constant.TransactionStatus.PENDING
           """)
    int resolvePending(UUID id, TransactionStatus status, String reason, LocalDateTime now);

    @Transactional
    @Modifying
    @Query("""
           UPDATE Transactions t
           SET t.status = com.gpay.payment.constant.TransactionStatus.EXPIRED, t.failureReason = :reason, t.updatedAt = :now
           WHERE t.status = com.gpay.payment.constant.TransactionStatus.PENDING
             AND t.type = com.gpay.payment.constant.TransactionType.TOPUP
             AND t.createdAt < :cutoff
           """)
    int expirePendingTopups(LocalDateTime cutoff, String reason, LocalDateTime now);

    @Query("""
           SELECT t FROM Transactions t
           WHERE t.status = com.gpay.payment.constant.TransactionStatus.PENDING
             AND t.type = com.gpay.payment.constant.TransactionType.TRANSFER
             AND t.createdAt < :cutoff
           """)
    List<Transactions> findPendingTransfersCreatedBefore(LocalDateTime cutoff);
}
