package com.gpay.notification.repository;

import com.gpay.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Idempotent insert keyed by UNIQUE (transaction_id, type); returns 0 for a redelivered event.
     * ON CONFLICT instead of catching the violation: a failed INSERT would abort the whole Postgres TX.
     */
    @Modifying
    @Query(value = """
           INSERT INTO notification.notifications
               (id, event_id, user_id, type, title, body, amount, currency, transaction_id, is_read, created_at)
           VALUES (:#{#n.id}, :#{#n.eventId}, :#{#n.userId}, :#{#n.type.name()}, :#{#n.title}, :#{#n.body},
                   :#{#n.amount}, :#{#n.currency}, :#{#n.transactionId}, FALSE, :#{#n.createdAt})
           ON CONFLICT (transaction_id, type) DO NOTHING
           """, nativeQuery = true)
    int insertIfAbsent(@Param("n") Notification n);

    Page<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    long countByUserIdAndReadFalse(UUID userId);

    boolean existsByIdAndUserId(UUID id, UUID userId);

    /** Ownership is part of the WHERE: another user's id behaves like a missing one. */
    @Modifying
    @Query("""
           UPDATE Notification n SET n.read = true, n.readAt = :now
           WHERE n.id = :id AND n.userId = :userId AND n.read = false
           """)
    int markRead(UUID id, UUID userId, LocalDateTime now);

    @Modifying
    @Query("UPDATE Notification n SET n.read = true, n.readAt = :now WHERE n.userId = :userId AND n.read = false")
    int markAllRead(UUID userId, LocalDateTime now);
}
