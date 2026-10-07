package com.gpay.auth.client;

import com.gpay.auth.exception.AuthException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

/** Provisions the wallet of a newly registered user via wallet-service's internal API. */
@Component
@Slf4j
public class WalletClient {
    private final RestTemplate restTemplate;
    private final String walletBaseUrl;
    private final String internalApiKey;

    public WalletClient(RestTemplate restTemplate,
                        @Value("${services.wallet-url}") String walletBaseUrl,
                        @Value("${internal.api-key}") String internalApiKey) {
        this.restTemplate = restTemplate;
        this.walletBaseUrl = walletBaseUrl;
        this.internalApiKey = internalApiKey;
    }

    /** Idempotent on wallet-service side; throws WalletProvisioningException if it cannot be reached or refuses. */
    public void createWallet(UUID userId) {
        String url = walletBaseUrl + "/api/v1/internal/wallet/create?userId=" + userId;
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Api-Key", internalApiKey);
        String traceId = MDC.get("traceId");
        if (traceId != null) headers.set("X-Trace-Id", traceId);

        try {
            restTemplate.postForEntity(url, new HttpEntity<>(headers), Void.class);
            log.info("Wallet provisioned for userId={}", userId);
        } catch (RestClientException e) {
            log.error("Wallet provisioning failed for userId={}: {}", userId, e.getMessage());
            throw new AuthException.WalletProvisioningException("Registration temporarily unavailable, please retry");
        }
    }
}
