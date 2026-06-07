package com.gpay.payment.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
public class PaymentGatewayClient {
    private final RestTemplate gatewayRestTemplate;
    private final String gatewayBaseUrl;

    public PaymentGatewayClient(RestTemplate gatewayRestTemplate,@Value("${services.payment-gateway-url}") String gatewayBaseUrl) {
        this.gatewayRestTemplate = gatewayRestTemplate;
        this.gatewayBaseUrl = gatewayBaseUrl;
    }

    public Map<String, Object> requestTopup(UUID transactionId, BigDecimal amount, String scenario) {
        String url = gatewayBaseUrl + "/gateway/topup";
        Map<String, Object> body = Map.of(
                "transactionId", transactionId.toString(),
                "amount", amount,
                "scenario", scenario
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String traceId = MDC.get("traceId");
        if (traceId != null) headers.set("X-Trace-Id", traceId);

        ResponseEntity<Map> response = gatewayRestTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

        log.info("Gateway response for transactionId={}: {}", transactionId, response.getBody());
        return response.getBody();
    }

}
