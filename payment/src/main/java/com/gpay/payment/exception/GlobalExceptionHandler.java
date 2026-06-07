package com.gpay.payment.exception;

import com.gpay.payment.dto.PaymentDTO.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage).collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(ApiResponse.error(errors));
    }

    @ExceptionHandler(PaymentException.RateLimitExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleRateLimit(PaymentException.RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "60")
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(PaymentException.DailyLimitExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleDailyLimit(PaymentException.DailyLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(PaymentException.InsufficientBalanceException.class)
    public ResponseEntity<ApiResponse<Void>> handleInsufficient(PaymentException.InsufficientBalanceException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(PaymentException.InvalidTransferException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidTransfer(PaymentException.InvalidTransferException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(PaymentException.TransferFailedException.class)
    public ResponseEntity<ApiResponse<Void>> handleTransferFailed(PaymentException.TransferFailedException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(PaymentException.WebhookSignatureException.class)
    public ResponseEntity<ApiResponse<Void>> handleWebhookSig(PaymentException.WebhookSignatureException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(PaymentException.WebhookException.class)
    public ResponseEntity<ApiResponse<Void>> handleWebhook(PaymentException.WebhookException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(PaymentException.TransactionNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(PaymentException.TransactionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        log.error("Unhandled exception in payment-service", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error("Internal server error"));
    }

}
