package com.gpay.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpay.payment.dto.PaymentDTO;
import com.gpay.payment.dto.PaymentDTO.ApiResponse;
import com.gpay.payment.dto.PaymentDTO.TransactionResponse;
import com.gpay.payment.exception.PaymentException;
import com.gpay.payment.service.IdempotencyService;
import com.gpay.payment.service.RateLimitService;
import com.gpay.payment.service.TransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/transfer")
@RequiredArgsConstructor
public class TransferController {
    private final TransferService transferService;
    private final IdempotencyService idempotencyService;
    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<ApiResponse<TransactionResponse>> transfer(
            @Valid @RequestBody PaymentDTO.TransferRequest request,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            Authentication auth) throws Exception {

        UUID userId = UUID.fromString(auth.getName());

        // Rate limit check
        if (!rateLimitService.checkAndIncrementRateLimit(userId)) {
            throw new PaymentException.RateLimitExceededException(
                    "Too many requests. Max 5 payment requests per minute. Retry after 60 seconds.");
        }

        // Idempotency check
        if (idempotencyService.exists(idempotencyKey)) {
            var cached = idempotencyService.getResponse(idempotencyKey);
            if (cached.isPresent()) {
                log.info("Duplicate transfer request idempotencyKey={}", idempotencyKey);
                TransactionResponse cachedResp = objectMapper.readValue(cached.get(), TransactionResponse.class);
                return ResponseEntity.ok(ApiResponse.ok("Duplicate request - returning cached response", cachedResp));
            }
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error("Request is still being processed."));
        }

        if (!idempotencyService.tryLock(idempotencyKey)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error("Duplicate request detected"));
        }

        TransactionResponse response = transferService.transfer(userId, request, idempotencyKey);
        idempotencyService.saveResponse(idempotencyKey, response);

        return ResponseEntity.ok(ApiResponse.ok("Transfer successful", response));
    }

}
