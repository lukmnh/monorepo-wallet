package com.gpay.paymentgateway.service.Impl;

import com.gpay.paymentgateway.service.GatewayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class GatewayServiceImpl implements GatewayService {
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${gateway.secret}")
    private String secret;

    @Value("${gateway.webhook-url}")
    private String webhookUrl;

    @Override
    public String initiateTopup(String transactionId, BigDecimal amount, String scenario) {
        String gatewayRef = "GW-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("Gateway received topup txnId={} amount={} scenario={} gatewayRef={}",
                transactionId, amount, scenario, gatewayRef);

        sendWebhookAsync(gatewayRef, amount, scenario);
        return gatewayRef;
    }

    @Async
    public void sendWebhookAsync(String gatewayRef, BigDecimal amount, String scenario) {
        switch (scenario) {
            case "SUCCESS" -> {
                sleep(1500);
                sendWebhook(gatewayRef, "SUCCESS", amount);
            }
            case "FAILED" -> {
                sleep(1000);
                sendWebhook(gatewayRef, "FAILED", amount);
            }
            case "TIMEOUT" -> {
                log.info("Gateway TIMEOUT scenario — no webhook will be sent for gatewayRef={}", gatewayRef);
            }
            default -> log.warn("Unknown scenario: {}", scenario);
        }
    }

    private void sendWebhook(String gatewayRef, String status, BigDecimal amount) {
        try {
            String signature = computeHmac(gatewayRef, status, amount);

            Map<String, Object> payload = Map.of(
                    "gatewayRef", gatewayRef,
                    "status", status,
                    "amount", amount
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Webhook-Signature", signature);

            ResponseEntity<String> response = restTemplate.exchange(
                    webhookUrl, HttpMethod.POST,
                    new HttpEntity<>(payload, headers), String.class);

            log.info("Webhook sent gatewayRef={} status={} → HTTP {}", gatewayRef, status, response.getStatusCode());
        } catch (Exception e) {
            log.error("Failed to send webhook for gatewayRef={}: {}", gatewayRef, e.getMessage());
        }
    }

    // hmac signature
    private String computeHmac(String gatewayRef, String status, BigDecimal amount) throws Exception {
        String data = gatewayRef + ":" + status + ":" + amount.toPlainString();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
