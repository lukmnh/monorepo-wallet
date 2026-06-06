package com.gpay.wallets.controller;

import com.gpay.auth.dto.AuthDTO.ApiResponse;
import com.gpay.wallets.dto.WalletDTO.BalanceResponse;
import com.gpay.wallets.dto.WalletDTO.MutationResponse;
import com.gpay.wallets.dto.WalletDTO.PageResponse;
import com.gpay.wallets.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(value = "/v1/api/wallet")
@RequiredArgsConstructor
public class WalletController {
    private final WalletService walletService;

    @GetMapping("/balance")
    public ResponseEntity<ApiResponse<BalanceResponse>> getBalance(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(ApiResponse.ok("Balance retrieved", walletService.getBalance(userId)));
    }

    @GetMapping("/api/v1/wallet/mutations")
    public ResponseEntity<ApiResponse<PageResponse<MutationResponse>>> getMutations(
            Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        if (size > 100) size = 100;
        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(ApiResponse.ok("Mutations retrieved",
                walletService.getMutations(userId, page, size)));
    }
}
