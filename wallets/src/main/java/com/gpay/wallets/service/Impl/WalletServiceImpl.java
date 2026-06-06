package com.gpay.wallets.service.Impl;

import com.gpay.wallets.constant.Type;
import com.gpay.wallets.dto.WalletDTO.*;
import com.gpay.wallets.entity.Mutations;
import com.gpay.wallets.entity.Wallets;
import com.gpay.wallets.exception.WalletException;
import com.gpay.wallets.repository.MutationRepository;
import com.gpay.wallets.repository.WalletRepository;
import com.gpay.wallets.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WalletServiceImpl implements WalletService {

    private final WalletRepository walletRepository;
    private final MutationRepository mutationRepository;

    @Override
    @Transactional
    public BalanceResponse createWallet(UUID userId) {
        if (walletRepository.findByUserId(userId).isPresent()) {
            throw new WalletException.WalletAlreadyExistsException("Wallet already exists for user: " + userId);
        }
        Wallets wallet = Wallets.builder().userId(userId).build();
        wallet = walletRepository.save(wallet);
        log.info("Wallet created for userId={}", userId);
        return toBalanceResponse(wallet);
    }

    @Override
    @Transactional
    public BalanceResponse getBalance(UUID userId) {
        Wallets wallet = findWalletByUserId(userId);
        return toBalanceResponse(wallet);
    }

    @Override
    @Transactional
    public PageResponse<MutationResponse> getMutations(UUID userId, int page, int size) {
        Wallets wallet = findWalletByUserId(userId);
        Page<Mutations> mutations = mutationRepository.findByWalletIdOrderByCreatedAtDesc(
                wallet.getId(), PageRequest.of(page, size));
        return new PageResponse<>(
                mutations.getContent().stream().map(this::toMutationResponse).toList(),
                page, size,
                mutations.getTotalElements(),
                mutations.getTotalPages(),
                mutations.isLast()
        );
    }

    @Override
    @Transactional
    public BalanceResponse credit(CreditDebitRequest request) {
        if (request.referenceId() != null && mutationRepository.existsByReferenceId(request.referenceId())) {
            log.warn("Duplicate credit referenceId={}, skipping", request.referenceId());
            Wallets wallet = walletRepository.findByUserId(request.userId())
                    .orElseThrow(() -> new WalletException
                            .WalletNotFoundException("Wallet not found"));
            return toBalanceResponse(wallet);
        }

        Wallets wallet = walletRepository.findByUserIdForUpdate(request.userId())
                .orElseThrow(() -> new WalletException.WalletNotFoundException("Wallet not found for userId: " + request.userId()));

        BigDecimal before = wallet.getBalance();
        BigDecimal after = before.add(request.amount());
        wallet.setBalance(after);
        walletRepository.save(wallet);

        recordMutation(wallet, Type.CREDIT, request.amount(), before, after,
                request.referenceId(), request.description());

        log.info("Credit userId={} amount={} balanceAfter={}", request.userId(), request.amount(), after);
        return toBalanceResponse(wallet);
    }

    @Override
    @Transactional
    public BalanceResponse debit(CreditDebitRequest request) {
        Wallets wallet = walletRepository.findByUserIdForUpdate(request.userId())
                .orElseThrow(() -> new WalletException.WalletNotFoundException("Wallet not found for userId: " + request.userId()));

        if (wallet.getBalance().compareTo(request.amount()) < 0) {
            throw new WalletException.InsufficientBalanceException(
                    "Insufficient balance. Current: " + wallet.getBalance() + ", Required: " + request.amount());
        }

        BigDecimal before = wallet.getBalance();
        BigDecimal after = before.subtract(request.amount());
        wallet.setBalance(after);
        walletRepository.save(wallet);

        recordMutation(wallet, Type.DEBIT, request.amount(), before, after,
                request.referenceId(), request.description());

        log.info("Debit userId={} amount={} balanceAfter={}", request.userId(), request.amount(), after);
        return toBalanceResponse(wallet);
    }

    @Override
    @Transactional
    public void atomicTransfer(AtomicTransferRequest request) {
        UUID fromId = request.fromUserId();
        UUID toId = request.toUserId();

        if (fromId.equals(toId)) {
            throw new WalletException.InvalidTransferException("Cannot transfer to the same wallet");
        }

        UUID firstLock = fromId.compareTo(toId) < 0 ? fromId : toId;
        UUID secondLock = fromId.compareTo(toId) < 0 ? toId : fromId;

        Wallets first = walletRepository.findByUserIdForUpdate(firstLock)
                .orElseThrow(() -> new WalletException.WalletNotFoundException("Wallet not found: " + firstLock));
        Wallets second = walletRepository.findByUserIdForUpdate(secondLock)
                .orElseThrow(() -> new WalletException.WalletNotFoundException("Wallet not found: " + secondLock));

        Wallets fromWallet = fromId.equals(firstLock) ? first : second;
        Wallets toWallet = toId.equals(firstLock) ? first : second;

        if (fromWallet.getBalance().compareTo(request.amount()) < 0) {
            throw new WalletException.InsufficientBalanceException(
                    "Insufficient balance. Current: " + fromWallet.getBalance() + ", Required: " + request.amount());
        }

        BigDecimal fromBefore = fromWallet.getBalance();
        BigDecimal fromAfter = fromBefore.subtract(request.amount());
        fromWallet.setBalance(fromAfter);

        BigDecimal toBefore = toWallet.getBalance();
        BigDecimal toAfter = toBefore.add(request.amount());
        toWallet.setBalance(toAfter);

        walletRepository.save(fromWallet);
        walletRepository.save(toWallet);

        String refId = request.referenceId();
        recordMutation(fromWallet, Type.DEBIT, request.amount(), fromBefore, fromAfter,
                refId, "Transfer out: " + request.description());
        recordMutation(toWallet, Type.CREDIT, request.amount(), toBefore, toAfter,
                refId, "Transfer in: " + request.description());

        log.info("AtomicTransfer from={} to={} amount={}", fromId, toId, request.amount());
    }

    private void recordMutation(Wallets wallet, Type type, BigDecimal amount,
                                BigDecimal before, BigDecimal after, String refId, String desc) {
        Mutations mutation = Mutations.builder()
                .wallet(wallet)
                .type(type)
                .amount(amount)
                .balanceBefore(before)
                .balanceAfter(after)
                .referenceId(refId)
                .description(desc)
                .build();
        mutationRepository.save(mutation);
    }

    private Wallets findWalletByUserId(UUID userId) {
        return walletRepository.findByUserId(userId)
                .orElseGet(() -> {
                    try {
                        log.info("Wallet not found for userId={}, auto-creating", userId);
                        return walletRepository.save(
                                Wallets.builder().userId(userId).build());
                    } catch (Exception e) {
                        log.warn("Race condition on wallet create for userId={}, fetching existing", userId);
                        return walletRepository.findByUserId(userId)
                                .orElseThrow(() -> new WalletException
                                        .WalletNotFoundException("Wallet not found for userId: " + userId));
                    }
                });
    }

    private BalanceResponse toBalanceResponse(Wallets wallet) {
        return new BalanceResponse(wallet.getId(), wallet.getUserId(), wallet.getBalance(), wallet.getUpdatedAt());
    }

    private MutationResponse toMutationResponse(Mutations m) {
        return new MutationResponse(m.getId(), m.getType(), m.getAmount(),
                m.getBalanceBefore(), m.getBalanceAfter(), m.getReferenceId(),
                m.getDescription(), m.getCreatedAt());
    }
}
