package com.gpay.wallets.dto;

import com.gpay.wallets.constant.Type;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public class WalletDTO {
    public record BalanceResponse(
            UUID walletId,
            UUID userId,
            BigDecimal balance,
            LocalDateTime updatedAt
    ) {}

    public record MutationResponse(
            UUID id,
            Type type,
            BigDecimal amount,
            BigDecimal balanceBefore,
            BigDecimal balanceAfter,
            String referenceId,
            String description,
            LocalDateTime createdAt
    ) {}

    public record CreditDebitRequest(
            @NotNull UUID userId,
            @NotNull @DecimalMin(value = "0.01", message = "Amount must be positive") BigDecimal amount,
            String referenceId,
            String description
    ) {}

    public record AtomicTransferRequest(
            @NotNull UUID fromUserId,
            @NotNull UUID toUserId,
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
            String referenceId,
            String description
    ) {}

    public record PageResponse<T>(
            java.util.List<T> content,
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
