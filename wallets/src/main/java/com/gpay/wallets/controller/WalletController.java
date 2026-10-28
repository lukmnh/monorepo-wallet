package com.gpay.wallets.controller;

import com.gpay.wallets.dto.WalletDTO.ApiResponse;
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
@RequestMapping(value = "/api/v1/wallet")
@RequiredArgsConstructor
public class WalletController {
    private static final int MAX_PAGE_SIZE = 100;

    private final WalletService walletService;

    @GetMapping("/balance")
    public ResponseEntity<ApiResponse<BalanceResponse>> getBalance(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(ApiResponse.ok("Balance retrieved", walletService.getBalance(userId)));
    }

    @GetMapping("/mutations")
    public ResponseEntity<ApiResponse<PageResponse<MutationResponse>>> getMutations(
            Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        // Clamp instead of failing: PageRequest.of throws on page < 0 or size < 1
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(ApiResponse.ok("Mutations retrieved",
                walletService.getMutations(userId, safePage, safeSize)));
    }
}
