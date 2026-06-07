package com.gpay.payment.service;

import com.gpay.payment.dto.PaymentDTO.TransactionResponse;
import com.gpay.payment.dto.PaymentDTO.TransferRequest;

import java.util.UUID;

public interface TransferService {
    TransactionResponse transfer(UUID fromUserId, TransferRequest request, String idempotencyKey);
}
