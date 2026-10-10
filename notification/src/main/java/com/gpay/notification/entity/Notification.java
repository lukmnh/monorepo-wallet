package com.gpay.notification.entity;

import com.gpay.notification.constant.NotificationType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "notifications", schema = "notification",
        uniqueConstraints = @UniqueConstraint(name = "uq_notifications_txn_type",
                columnNames = {"transaction_id", "type"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {
    // Assigned in Java: rows are written with a native INSERT ... ON CONFLICT DO NOTHING
    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 255)
    private String body;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    // When the payment succeeded, not when the event was relayed
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
