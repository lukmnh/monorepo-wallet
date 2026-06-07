package com.gpay.payment.client;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
public class AuditClient {
    private final RestTemplate restTemplate;
    private final String auditBaseUrl;
    private final String internalApiKey;

    public AuditClient(
            RestTemplate restTemplate,
            @Value("${services.audit-url}") String auditBaseUrl,
            @Value("${internal.api-key}") String internalApiKey) {
        this.restTemplate = restTemplate;
        this.auditBaseUrl = auditBaseUrl;
        this.internalApiKey = internalApiKey;
    }

    @Async
    public void log(UUID userId, UUID transactionId, String action, String status,
                    Object requestPayload, Object responsePayload, long durationMs, String ipAddress) {
        try {
            String url = auditBaseUrl + "/api/v1/internal/audit";
            Map<String, Object> body = new HashMap<>();
            body.put("traceId", MDC.get("traceId"));
            body.put("userId", userId);
            body.put("transactionId", transactionId);
            body.put("service", "payment-service");
            body.put("action", action);
            body.put("status", status);
            body.put("requestPayload", requestPayload);
            body.put("responsePayload", responsePayload);
            body.put("durationMs", durationMs);
            body.put("ipAddress", ipAddress);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Internal-Api-Key", internalApiKey);
            headers.set("X-Trace-Id", MDC.get("traceId") != null ? MDC.get("traceId") : "");

            restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        } catch (Exception e) {
            log.error("Failed to send audit log to audit-service: {}", e.getMessage());
        }
    }
}
