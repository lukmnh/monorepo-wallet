package com.gpay.payment.service;

import com.gpay.payment.dto.PaymentDTO.TransactionResponse;

import java.util.UUID;

public interface TopupService {
    TransactionResponse topup(UUID userId, com.gpay.payment.dto.PaymentDTO.TopupRequest request, String idempotencyKey);
    
}
