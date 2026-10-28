package com.gpay.payment.service.Impl;

import com.gpay.payment.constant.TransactionType;
import com.gpay.payment.dto.PaymentDTO.TransactionResponse;
import com.gpay.payment.entity.Transactions;
import com.gpay.payment.exception.PaymentException;

import java.math.BigDecimal;

/** Answers a repeated idempotency key from the stored transaction (its current state, not a stale snapshot). */
final class IdempotentReplay {
    private IdempotentReplay() {}

    static TransactionResponse replay(Transactions existing, TransactionType type, BigDecimal amount) {
        // Same key, different request: refuse instead of silently returning an unrelated result
        if (existing.getType() != type || existing.getAmount().compareTo(amount) != 0) {
            throw new PaymentException.IdempotencyKeyReusedException(
                    "Idempotency key was already used for a different request");
        }
        return TransactionResponse.from(existing);
    }
}
