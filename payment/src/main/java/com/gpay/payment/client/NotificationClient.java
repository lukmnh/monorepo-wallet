package com.gpay.payment.client;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
public class NotificationClient {
    private final RestTemplate restTemplate;
    private final String eventsUrl;
    private final String internalApiKey;

    public NotificationClient(
            RestTemplate restTemplate,
            @Value("${services.notification-url}") String notificationBaseUrl,
            @Value("${internal.api-key}") String internalApiKey) {
        this.restTemplate = restTemplate;
        this.eventsUrl = notificationBaseUrl + "/api/v1/internal/notifications/events";
        this.internalApiKey = internalApiKey;
    }

    /** Sends the stored outbox payload as-is (already JSON). Throws RestClientException on failure. */
    public void publish(String eventJson) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Api-Key", internalApiKey);
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            headers.set("X-Trace-Id", traceId);
        }
        restTemplate.exchange(eventsUrl, HttpMethod.POST, new HttpEntity<>(eventJson, headers), Map.class);
    }
}
