package com.gpay.payment.dto;

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
            BigDecimal amount,

            @NotBlank(message = "Scenario is required")
            @Pattern(regexp = "SUCCESS|FAILED|TIMEOUT", message = "Scenario must be SUCCESS, FAILED, or TIMEOUT")
            String scenario,

            String description
    ) {}

    public record TransferRequest(
            @NotNull(message = "Target user ID is required")
            UUID toUserId,

            @NotNull(message = "Amount is required")
            @DecimalMin(value = "1000", message = "Minimum transfer is 1,000")
            BigDecimal amount,

            String description
    ) {}

    public record TransactionResponse(
            UUID transactionId,
            TransactionType type,
            TransactionStatus status,
            BigDecimal amount,
            String description,
            LocalDateTime createdAt
    ) {}

    public record WebhookPayload(
            String gatewayRef,
            String status,   // SUCCESS or FAILED
            BigDecimal amount
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
