package com.gpay.wallets.controller;

import com.gpay.wallets.dto.WalletDTO;
import com.gpay.wallets.dto.WalletDTO.ApiResponse;
import com.gpay.wallets.dto.WalletDTO.BalanceResponse;
import com.gpay.wallets.service.WalletService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(value = "/api/v1/internal/wallet")
@RequiredArgsConstructor
public class InternalWalletController {
    private final WalletService walletService;

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<BalanceResponse>> createWallet(@RequestParam UUID userId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Wallet created", walletService.createWallet(userId)));
    }

    @PostMapping("/credit")
    public ResponseEntity<ApiResponse<BalanceResponse>> credit(@Valid @RequestBody WalletDTO.CreditDebitRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Credit successful", walletService.credit(request)));
    }

    @PostMapping("/debit")
    public ResponseEntity<ApiResponse<BalanceResponse>> debit(@Valid @RequestBody WalletDTO.CreditDebitRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Debit successful", walletService.debit(request)));
    }

    @PostMapping("/transfer")
    public ResponseEntity<ApiResponse<Void>> atomicTransfer(@Valid @RequestBody WalletDTO.AtomicTransferRequest request) {
        walletService.atomicTransfer(request);
        return ResponseEntity.ok(ApiResponse.ok("Transfer successful", null));
    }

}
