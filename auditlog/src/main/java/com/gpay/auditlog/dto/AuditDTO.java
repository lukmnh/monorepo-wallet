package com.gpay.auditlog.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;
import java.util.UUID;

public class AuditDTO {
    public record AuditLogRequest(
            String traceId,
            UUID userId,
            UUID transactionId,
            @NotBlank String service,
            @NotBlank String action,
            String status,
            Object requestPayload,
            Object responsePayload,
            String errorMessage,
            Long durationMs,
            String ipAddress
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
