package com.gpay.payment.controller;

import com.gpay.payment.dto.PaymentDTO.ApiResponse;
import com.gpay.payment.dto.PaymentDTO.TransactionResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Locale;

/**
 * Maps a transaction's state to the HTTP answer. A replayed idempotency key gets the same mapping,
 * so a client sees a consistent result no matter how often it retries.
 */
final class TransactionHttp {
    private TransactionHttp() {}

    static ResponseEntity<ApiResponse<TransactionResponse>> respond(TransactionResponse txn, String operation) {
        return switch (txn.status()) {
            case SUCCESS -> ResponseEntity.ok(ApiResponse.ok(operation + " successful", txn));
            case PENDING -> ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(ApiResponse.ok(operation + " is pending confirmation", txn));
            case FAILED, EXPIRED -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(ApiResponse.fail(operation + " " + txn.status().name().toLowerCase(Locale.ROOT)
                            + ": " + txn.failureReason(), txn));
        };
    }
}
