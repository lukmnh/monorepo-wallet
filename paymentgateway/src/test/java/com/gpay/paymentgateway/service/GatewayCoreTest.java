package com.gpay.paymentgateway.service;

import com.gpay.paymentgateway.service.Impl.GatewayServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GatewayCoreTest {
    private static final String SECRET = "test-secret";
    private static final String URL = "http://payment/api/v1/webhook/topup";

    @Test
    void initiateReturnsGatewayRefAndDispatchesAsync() {
        WebhookDispatcher dispatcher = mock(WebhookDispatcher.class);
        GatewayServiceImpl service = new GatewayServiceImpl(dispatcher);

        String ref = service.initiateTopup("txn-1", new BigDecimal("50000"), "SUCCESS");

        assertThat(ref).matches("GW-[0-9A-F]{8}");
        verify(dispatcher).dispatch(ref, "txn-1", new BigDecimal("50000"), "SUCCESS");
    }

    @Test
    @SuppressWarnings("unchecked")
    void webhookIsSignedExactlyAsPaymentServiceVerifiesIt() throws Exception {
        RestTemplate rest = mock(RestTemplate.class);
        WebhookDispatcher dispatcher = new WebhookDispatcher(builderReturning(rest), SECRET, URL);

        // FAILED scenario: shortest delay (1 s) that still delivers a webhook
        dispatcher.dispatch("GW-ABC", "txn-1", new BigDecimal("50000.00"), "FAILED");

        var entity = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(rest).postForEntity(eq(URL), entity.capture(), eq(String.class));
        Map<String, Object> body = (Map<String, Object>) entity.getValue().getBody();
        assertThat(body).containsEntry("status", "FAILED").containsEntry("gatewayRef", "GW-ABC");

        // Contract with payment WebhookServiceImpl: HMAC-SHA256("gatewayRef:transactionId:status:amount")
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(
                mac.doFinal("GW-ABC:txn-1:FAILED:50000.00".getBytes(StandardCharsets.UTF_8)));
        assertThat(entity.getValue().getHeaders().getFirst("X-Webhook-Signature")).isEqualTo(expected);
    }

    @Test
    void timeoutScenarioSendsNoWebhook() {
        RestTemplate rest = mock(RestTemplate.class);
        WebhookDispatcher dispatcher = new WebhookDispatcher(builderReturning(rest), SECRET, URL);

        dispatcher.dispatch("GW-ABC", "txn-1", BigDecimal.TEN, "TIMEOUT");

        verifyNoInteractions(rest);
    }

    @Test
    void blankSecretFailsStartup() {
        assertThatThrownBy(() -> new WebhookDispatcher(builderReturning(mock(RestTemplate.class)), " ", URL))
                .isInstanceOf(IllegalStateException.class);
    }

    private static RestTemplateBuilder builderReturning(RestTemplate rest) {
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.connectTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.readTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.build()).thenReturn(rest);
        return builder;
    }
}
