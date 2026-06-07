package com.gpay.paymentgateway.service;

import java.math.BigDecimal;

public interface GatewayService {
    String initiateTopup(String transactionId, BigDecimal amount, String scenario);
}
