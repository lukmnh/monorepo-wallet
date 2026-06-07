package com.gpay.payment.controller;

import com.gpay.payment.dto.PaymentDTO;
import com.gpay.payment.dto.PaymentDTO.ApiResponse;
import com.gpay.payment.service.WebhookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/webhook")
@RequiredArgsConstructor
public class WebhookController {
    private final WebhookService webhookService;

    @PostMapping("/topup")
    public ResponseEntity<ApiResponse<Void>> handleTopupWebhook(
            @Valid @RequestBody PaymentDTO.WebhookPayload payload,
            @RequestHeader("X-Webhook-Signature") String signature) {

        log.info("Webhook received gatewayRef={} status={}", payload.gatewayRef(), payload.status());
        webhookService.processWebhook(payload, signature);
        return ResponseEntity.ok(ApiResponse.ok("Webhook processed", null));
    }
}
