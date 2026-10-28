package com.gpay.payment.service;

import com.gpay.payment.dto.PaymentDTO.TransactionResponse;
import com.gpay.payment.dto.PaymentDTO.TransferRequest;
import com.gpay.payment.entity.Transactions;

import java.util.UUID;

public interface TransferService {
    TransactionResponse transfer(UUID fromUserId, TransferRequest request, String idempotencyKey);

    /** Re-sends a PENDING transfer whose outcome is unknown (safe: wallet-service dedupes by referenceId). */
    void reconcile(Transactions pendingTransfer);
}
