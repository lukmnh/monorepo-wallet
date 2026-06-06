package com.gpay.wallets.service;

import com.gpay.wallets.dto.WalletDTO.*;

import java.util.UUID;

public interface WalletService {
    BalanceResponse createWallet(UUID userId);
    BalanceResponse getBalance(UUID userId);
    PageResponse<MutationResponse> getMutations(UUID userId, int page, int size);
    BalanceResponse credit(CreditDebitRequest request);
    BalanceResponse debit(CreditDebitRequest request);
    void atomicTransfer(AtomicTransferRequest request);
}
