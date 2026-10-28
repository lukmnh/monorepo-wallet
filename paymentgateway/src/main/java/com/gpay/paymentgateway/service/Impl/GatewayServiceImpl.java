package com.gpay.paymentgateway.service.Impl;

import com.gpay.paymentgateway.service.GatewayService;
import com.gpay.paymentgateway.service.WebhookDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class GatewayServiceImpl implements GatewayService {
    private final WebhookDispatcher webhookDispatcher;

    @Override
    public String initiateTopup(String transactionId, BigDecimal amount, String scenario) {
        String gatewayRef = "GW-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("Gateway received topup txnId={} amount={} scenario={} gatewayRef={}",
                transactionId, amount, scenario, gatewayRef);

        // Separate bean => goes through the @Async proxy; returns immediately so the caller gets gatewayRef first
        webhookDispatcher.dispatch(gatewayRef, transactionId, amount, scenario);
        return gatewayRef;
    }
}
