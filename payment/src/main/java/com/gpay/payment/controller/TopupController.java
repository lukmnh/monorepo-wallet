package com.gpay.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpay.payment.dto.PaymentDTO.ApiResponse;
import com.gpay.payment.dto.PaymentDTO.TransactionResponse;
import com.gpay.payment.exception.PaymentException;
import com.gpay.payment.service.IdempotencyService;
import com.gpay.payment.service.RateLimitService;
import com.gpay.payment.service.TopupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(value = "/api/v1/topup")
@RequiredArgsConstructor
@Slf4j
public class TopupController {
    private final TopupService topupService;
    private final IdempotencyService idempotencyService;
    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<ApiResponse<TransactionResponse>> topup(
            @Valid @RequestBody com.gpay.payment.dto.PaymentDTO.TopupRequest request,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            Authentication auth) throws Exception {

        UUID userId = UUID.fromString(auth.getName());

        // Rate limit check
        if (!rateLimitService.checkAndIncrementRateLimit(userId)) {
            int remaining = rateLimitService.getRemainingRateLimit(userId);
            throw new PaymentException.RateLimitExceededException(
                    "Too many requests. Max 5 per minute. Remaining: " + remaining + ". Retry after 60 seconds.");
        }

        // Idempotency check
        if (idempotencyService.exists(idempotencyKey)) {
            var cached = idempotencyService.getResponse(idempotencyKey);
            if (cached.isPresent()) {
                log.info("Duplicate topup request idempotencyKey={}, returning cached response", idempotencyKey);
                TransactionResponse cachedResp = objectMapper.readValue(cached.get(), TransactionResponse.class);
                return ResponseEntity.ok(ApiResponse.ok("Duplicate request - returning cached response", cachedResp));
            }

            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error("Request is still being processed. Please retry shortly."));
        }

        // try to acquire idempotency lock
        if (!idempotencyService.tryLock(idempotencyKey)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error("Duplicate request detected"));
        }

        TransactionResponse response = topupService.topup(userId, request, idempotencyKey);
        idempotencyService.saveResponse(idempotencyKey, response);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok("Top-up initiated. Waiting for payment gateway callback.", response));
    }
}
