package com.gpay.payment.service;

import com.gpay.payment.dto.PaymentDTO.WebhookPayload;

public interface WebhookService {
    void processWebhook(WebhookPayload payload, String signature);
    
}
