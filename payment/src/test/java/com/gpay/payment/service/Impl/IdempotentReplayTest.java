package com.gpay.payment.service.Impl;

import com.gpay.payment.constant.TransactionStatus;
import com.gpay.payment.constant.TransactionType;
import com.gpay.payment.dto.PaymentDTO.TransactionResponse;
import com.gpay.payment.entity.Transactions;
import com.gpay.payment.exception.PaymentException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotentReplayTest {
    private final Transactions stored = Transactions.builder().id(UUID.randomUUID()).type(TransactionType.TOPUP)
            .status(TransactionStatus.SUCCESS).amount(new BigDecimal("50000.00")).build();

    @Test
    void sameRequestReplaysCurrentState() {
        // 50000 vs 50000.00: compared by value, not scale
        TransactionResponse res = IdempotentReplay.replay(stored, TransactionType.TOPUP, new BigDecimal("50000"));

        assertThat(res.transactionId()).isEqualTo(stored.getId());
        assertThat(res.status()).isEqualTo(TransactionStatus.SUCCESS);
    }

    @Test
    void differentAmountIsRejected() {
        assertThatThrownBy(() -> IdempotentReplay.replay(stored, TransactionType.TOPUP, new BigDecimal("20000")))
                .isInstanceOf(PaymentException.IdempotencyKeyReusedException.class);
    }

    @Test
    void differentTypeIsRejected() {
        assertThatThrownBy(() -> IdempotentReplay.replay(stored, TransactionType.TRANSFER, new BigDecimal("50000")))
                .isInstanceOf(PaymentException.IdempotencyKeyReusedException.class);
    }
}
