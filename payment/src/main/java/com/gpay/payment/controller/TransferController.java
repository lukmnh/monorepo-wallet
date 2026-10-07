package com.gpay.payment.controller;

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

    @PostMapping
    public ResponseEntity<ApiResponse<TransactionResponse>> transfer(
            @Valid @RequestBody PaymentDTO.TransferRequest request,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            Authentication auth) {

        UUID userId = UUID.fromString(auth.getName());

        if (!rateLimitService.tryAcquire(userId)) {
            throw new PaymentException.RateLimitExceededException("Too many payment requests. Max "
                    + rateLimitService.getLimitPerMinute() + " per minute. Retry after 60 seconds.");
        }

        // Only blocks concurrent duplicates; a finished request with the same key is replayed by the service
        String lockToken = idempotencyService.tryLock(userId, idempotencyKey);
        if (lockToken == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error("A request with this idempotency key is still being processed. Retry shortly."));
        }
        try {
            return TransactionHttp.respond(transferService.transfer(userId, request, idempotencyKey), "Transfer");
        } finally {
            idempotencyService.release(userId, idempotencyKey, lockToken);
        }
    }
}
