package com.gpay.paymentgateway.controller;

import com.gpay.paymentgateway.service.GatewayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/gateway")
@RequiredArgsConstructor
public class GatewayController {
    private final GatewayService gatewayService;

    @PostMapping("/topup")
    public ResponseEntity<Map<String, Object>> topup(@RequestBody Map<String, Object> request) {
        String transactionId = (String) request.get("transactionId");
        BigDecimal amount = new BigDecimal(request.get("amount").toString());
        String scenario = (String) request.getOrDefault("scenario", "SUCCESS");

        String gatewayRef = gatewayService.initiateTopup(transactionId, amount, scenario);

        return ResponseEntity.ok(Map.of(
                "gatewayRef", gatewayRef,
                "status", "ACCEPTED",
                "message", "Payment request received. Webhook will be sent shortly."
        ));
    }
}
