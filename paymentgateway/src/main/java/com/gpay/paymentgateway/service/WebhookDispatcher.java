package com.gpay.paymentgateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

/**
 * Simulates the gateway's asynchronous callback, like a real PSP:
 * delivered after the initiate call has returned, signed with HMAC-SHA256, retried with backoff on failure.
 */
@Component
@Slf4j
public class WebhookDispatcher {
    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 2_000;

    private final RestTemplate restTemplate;
    private final String secret;
    private final String webhookUrl;

    public WebhookDispatcher(RestTemplateBuilder builder,
                             @Value("${gateway.secret}") String secret,
                             @Value("${gateway.webhook-url}") String webhookUrl) {
        this.restTemplate = builder
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(10))
                .build();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("MOCK_GATEWAY_SECRET must be set");
        }
        this.secret = secret;
        this.webhookUrl = webhookUrl;
    }

    @Async
    public void dispatch(String gatewayRef, String transactionId, BigDecimal amount, String scenario) {
        switch (scenario) {
            case "SUCCESS" -> {
                sleep(1500);
                deliver(gatewayRef, transactionId, "SUCCESS", amount);
            }
            case "FAILED" -> {
                sleep(1000);
                deliver(gatewayRef, transactionId, "FAILED", amount);
            }
            case "TIMEOUT" -> log.info("Gateway TIMEOUT scenario — no webhook will be sent for gatewayRef={}", gatewayRef);
            default -> log.warn("Unknown scenario: {}", scenario);
        }
    }

    private void deliver(String gatewayRef, String transactionId, String status, BigDecimal amount) {
        Map<String, Object> payload = Map.of(
                "gatewayRef", gatewayRef,
                "transactionId", transactionId,
                "status", status,
                "amount", amount
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Webhook-Signature", sign(gatewayRef, transactionId, status, amount));

        long backoff = INITIAL_BACKOFF_MS;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                restTemplate.postForEntity(webhookUrl, new HttpEntity<>(payload, headers), String.class);
                log.info("Webhook delivered gatewayRef={} status={} attempt={}", gatewayRef, status, attempt);
                return;
            } catch (RestClientException e) {
                log.warn("Webhook attempt {}/{} failed gatewayRef={}: {}", attempt, MAX_ATTEMPTS, gatewayRef, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleep(backoff);
                    backoff *= 2;
                }
            }
        }
        log.error("Webhook gave up after {} attempts gatewayRef={}", MAX_ATTEMPTS, gatewayRef);
    }

    // Signed fields: gatewayRef:transactionId:status:amount (must match payment-service WebhookServiceImpl)
    private String sign(String gatewayRef, String transactionId, String status, BigDecimal amount) {
        String data = gatewayRef + ":" + transactionId + ":" + status + ":" + amount.toPlainString();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
