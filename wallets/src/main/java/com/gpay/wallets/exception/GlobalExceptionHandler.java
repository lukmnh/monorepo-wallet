package com.gpay.wallets.exception;

import com.gpay.auth.dto.AuthDTO.ApiResponse;
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

    @ExceptionHandler(WalletException.WalletNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(WalletException.WalletNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(WalletException.WalletAlreadyExistsException.class)
    public ResponseEntity<ApiResponse<Void>> handleAlreadyExists(WalletException.WalletAlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(WalletException.InsufficientBalanceException.class)
    public ResponseEntity<ApiResponse<Void>> handleInsufficient(WalletException.InsufficientBalanceException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(WalletException.InvalidTransferException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidTransfer(WalletException.InvalidTransferException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(WalletException.UnauthorizedAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorized(WalletException.UnauthorizedAccessException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        log.error("Unhandled exception in wallet-service", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error("Internal server error"));
    }
}
