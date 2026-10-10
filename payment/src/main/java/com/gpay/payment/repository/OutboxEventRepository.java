package com.gpay.payment.repository;

import com.gpay.payment.constant.OutboxStatus;
import com.gpay.payment.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Joins the caller's TX (status change + event commit together).
     * ON CONFLICT: a second success path for the same transaction is a no-op, not a TX-aborting violation.
     */
    @Modifying
    @Query(value = """
           INSERT INTO payment.outbox_events
               (id, aggregate_id, event_type, payload, status, attempts, next_attempt_at, trace_id, created_at)
           VALUES (:id, :aggregateId, :eventType, CAST(:payload AS jsonb), 'PENDING', 0, :now, :traceId, :now)
           ON CONFLICT (aggregate_id, event_type) DO NOTHING
           """, nativeQuery = true)
    int insertIfAbsent(UUID id, UUID aggregateId, String eventType, String payload, String traceId, LocalDateTime now);

    /** SKIP LOCKED: concurrent relays (several replicas) claim disjoint batches instead of blocking. */
    @Query(value = """
           SELECT * FROM payment.outbox_events
           WHERE status = 'PENDING' AND next_attempt_at <= :now
           ORDER BY next_attempt_at
           LIMIT :limit
           FOR UPDATE SKIP LOCKED
           """, nativeQuery = true)
    List<OutboxEvent> lockDue(LocalDateTime now, int limit);

    /** Claim = push next_attempt_at forward, so the HTTP calls happen without holding the row locks. */
    @Modifying
    @Query("UPDATE OutboxEvent e SET e.nextAttemptAt = :leaseUntil WHERE e.id IN :ids")
    int lease(Collection<UUID> ids, LocalDateTime leaseUntil);

    @Transactional
    @Modifying
    @Query("""
           UPDATE OutboxEvent e SET e.status = com.gpay.payment.constant.OutboxStatus.PUBLISHED,
                  e.publishedAt = :now, e.lastError = null
           WHERE e.id = :id
           """)
    int markPublished(UUID id, LocalDateTime now);

    /** status = PENDING (retry at nextAttemptAt) or DEAD (give up). */
    @Transactional
    @Modifying
    @Query("""
           UPDATE OutboxEvent e SET e.status = :status, e.attempts = :attempts,
                  e.nextAttemptAt = :nextAttemptAt, e.lastError = :error
           WHERE e.id = :id AND e.status = com.gpay.payment.constant.OutboxStatus.PENDING
           """)
    int recordFailure(UUID id, OutboxStatus status, int attempts, LocalDateTime nextAttemptAt, String error);
}
