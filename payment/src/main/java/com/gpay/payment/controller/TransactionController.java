package com.gpay.payment.controller;

import com.gpay.payment.dto.PaymentDTO.ApiResponse;
import com.gpay.payment.dto.PaymentDTO.TransactionResponse;
import com.gpay.payment.entity.Transactions;
import com.gpay.payment.exception.PaymentException;
import com.gpay.payment.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {
    private final TransactionRepository transactionRepository;

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TransactionResponse>> getTransaction(
            @PathVariable UUID id, Authentication auth) {

        UUID userId = UUID.fromString(auth.getName());
        Transactions txn = transactionRepository.findById(id)
                .orElseThrow(() -> new PaymentException.TransactionNotFoundException("Transaction not found"));

        if (!txn.getUserId().equals(userId)) {
            throw new PaymentException.TransactionNotFoundException("Transaction not found");
        }

        return ResponseEntity.ok(ApiResponse.ok("Transaction retrieved",
                new TransactionResponse(txn.getId(), txn.getType(), txn.getStatus(),
                        txn.getAmount(), txn.getDescription(), txn.getCreatedAt())));
    }
}
