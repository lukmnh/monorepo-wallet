package com.gpay.payment.dto;

import com.gpay.payment.constant.OutboxEventType;
import com.gpay.payment.constant.TransactionStatus;
import com.gpay.payment.constant.TransactionType;
import com.gpay.payment.entity.Transactions;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public class PaymentDTO {
    public record TopupRequest(
            @NotNull(message = "Amount is required")
            @DecimalMin(value = "10000", message = "Minimum top-up is 10,000")
            @DecimalMax(value = "50000000", message = "Maximum top-up is 50,000,000")
            @Digits(integer = 17, fraction = 2, message = "Amount must have at most 2 decimal places")
            BigDecimal amount,

            @NotBlank(message = "Scenario is required")
            @Pattern(regexp = "SUCCESS|FAILED|TIMEOUT", message = "Scenario must be SUCCESS, FAILED, or TIMEOUT")
            String scenario,

            @Size(max = 255, message = "Description must be at most 255 characters")
            String description
    ) {}

    public record TransferRequest(
            @NotNull(message = "Target user ID is required")
            UUID toUserId,

            @NotNull(message = "Amount is required")
            @DecimalMin(value = "1000", message = "Minimum transfer is 1,000")
            @Digits(integer = 17, fraction = 2, message = "Amount must have at most 2 decimal places")
            BigDecimal amount,

            @Size(max = 200, message = "Description must be at most 200 characters")
            String description
    ) {}

    public record TransactionResponse(
            UUID transactionId,
            TransactionType type,
            TransactionStatus status,
            BigDecimal amount,
            String description,
            String failureReason,
            LocalDateTime createdAt
    ) {
        public static TransactionResponse from(Transactions txn) {
            return new TransactionResponse(txn.getId(), txn.getType(), txn.getStatus(), txn.getAmount(),
                    txn.getDescription(), txn.getFailureReason(), txn.getCreatedAt());
        }
    }

    /** Outbox payload for notification-service. counterpartyUserId = transfer recipient, null for top-up. */
    public record PaymentSucceededEvent(
            UUID eventId,
            OutboxEventType eventType,
            UUID transactionId,
            UUID userId,
            UUID counterpartyUserId,
            BigDecimal amount,
            String currency,
            LocalDateTime occurredAt
    ) {}

    /** Gateway callback. All fields are covered by the HMAC signature. */
    public record WebhookPayload(
            @NotBlank String gatewayRef,
            @NotNull UUID transactionId,
            @NotBlank @Pattern(regexp = "SUCCESS|FAILED") String status,
            @NotNull BigDecimal amount
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
        public static <T> ApiResponse<T> fail(String message, T data) {
            return new ApiResponse<>(false, message, data);
        }
    }
}
