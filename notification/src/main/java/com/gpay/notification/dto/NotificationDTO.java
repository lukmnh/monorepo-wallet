package com.gpay.notification.dto;

import com.gpay.notification.constant.NotificationType;
import com.gpay.notification.constant.PaymentEventType;
import com.gpay.notification.entity.Notification;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class NotificationDTO {

    /** Internal: payment-service outbox event. counterpartyUserId is the transfer recipient. */
    public record PaymentEvent(
            @NotNull UUID eventId,
            @NotNull PaymentEventType eventType,
            @NotNull UUID transactionId,
            @NotNull UUID userId,
            UUID counterpartyUserId,
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotNull LocalDateTime occurredAt
    ) {}

    public record IngestResponse(int created) {}

    public record NotificationResponse(
            UUID id,
            NotificationType type,
            String title,
            String body,
            BigDecimal amount,
            String currency,
            UUID transactionId,
            boolean read,
            LocalDateTime readAt,
            LocalDateTime createdAt
    ) {
        public static NotificationResponse from(Notification n) {
            return new NotificationResponse(n.getId(), n.getType(), n.getTitle(), n.getBody(), n.getAmount(),
                    n.getCurrency(), n.getTransactionId(), n.isRead(), n.getReadAt(), n.getCreatedAt());
        }
    }

    public record UnreadCountResponse(long unread) {}

    public record MarkAllReadResponse(int updated) {}

    public record PageResponse<T>(
            List<T> content,
            int page,
            int size,
            long totalElements,
            int totalPages,
            boolean last
    ) {}

    public record ApiResponse<T>(
            boolean success,
            String message,
            T data
    ) {
        public static <T> ApiResponse<T> ok(String message, T data) {
            return new ApiResponse<>(true, message, data);
        }
        public static <T> ApiResponse<T> error(String message) {
            return new ApiResponse<>(false, message, null);
        }
    }
}
