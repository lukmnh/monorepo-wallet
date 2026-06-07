package com.gpay.payment.client;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
public class WalletClient {
    private final RestTemplate restTemplate;
    private final String walletBaseUrl;
    private final String internalApiKey;

    public WalletClient(
            RestTemplate restTemplate,
            @Value("${services.wallet-url}") String walletBaseUrl,
            @Value("${internal.api-key}") String internalApiKey) {
        this.restTemplate = restTemplate;
        this.walletBaseUrl = walletBaseUrl;
        this.internalApiKey = internalApiKey;
    }

    public void createWallet(UUID userId) {
        String url = walletBaseUrl + "/api/v1/internal/wallet/create?userId=" + userId;
        restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(buildHeaders()), Map.class);
        log.info("Wallet created for userId={}", userId);
    }

    public void credit(UUID userId, BigDecimal amount, String referenceId, String description) {
        String url = walletBaseUrl + "/api/v1/internal/wallet/credit";
        Map<String, Object> body = Map.of(
                "userId", userId,
                "amount", amount,
                "referenceId", referenceId,
                "description", description
        );
        restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, buildHeaders()), Map.class);
        log.info("Credit sent to wallet-service userId={} amount={} ref={}", userId, amount, referenceId);
    }

    public void atomicTransfer(UUID fromUserId, UUID toUserId, BigDecimal amount,
                               String referenceId, String description) {
        String url = walletBaseUrl + "/api/v1/internal/wallet/transfer";
        Map<String, Object> body = Map.of(
                "fromUserId", fromUserId,
                "toUserId", toUserId,
                "amount", amount,
                "referenceId", referenceId,
                "description", description
        );
        restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, buildHeaders()), Map.class);
        log.info("AtomicTransfer sent to wallet-service from={} to={} amount={}", fromUserId, toUserId, amount);
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Api-Key", internalApiKey);
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            headers.set("X-Trace-Id", traceId);
        }
        return headers;
    }
}
