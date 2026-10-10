package com.gpay.wallets.service.Impl;

import com.gpay.wallets.constant.Type;
import com.gpay.wallets.dto.WalletDTO.AtomicTransferRequest;
import com.gpay.wallets.dto.WalletDTO.BalanceResponse;
import com.gpay.wallets.dto.WalletDTO.CreditDebitRequest;
import com.gpay.wallets.entity.Mutations;
import com.gpay.wallets.entity.Wallets;
import com.gpay.wallets.exception.WalletException;
import com.gpay.wallets.repository.MutationRepository;
import com.gpay.wallets.repository.WalletRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletServiceImplTest {
    @Mock WalletRepository walletRepository;
    @Mock MutationRepository mutationRepository;
    @InjectMocks WalletServiceImpl service;

    private static Wallets wallet(UUID userId, String balance) {
        return Wallets.builder().id(UUID.randomUUID()).userId(userId).balance(new BigDecimal(balance)).build();
    }

    @Nested
    class Credit {
        @Test
        void addsAmountAndWritesLedgerEntry() {
            UUID userId = UUID.randomUUID();
            Wallets w = wallet(userId, "100.00");
            when(walletRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(w));

            BalanceResponse res = service.credit(new CreditDebitRequest(userId, new BigDecimal("50.00"), "ref-1", "topup"));

            assertThat(res.balance()).isEqualByComparingTo("150.00");
            ArgumentCaptor<Mutations> m = ArgumentCaptor.forClass(Mutations.class);
            verify(mutationRepository).save(m.capture());
            assertThat(m.getValue().getType()).isEqualTo(Type.CREDIT);
            assertThat(m.getValue().getBalanceBefore()).isEqualByComparingTo("100.00");
            assertThat(m.getValue().getBalanceAfter()).isEqualByComparingTo("150.00");
        }

        @Test
        void duplicateReferenceIsNoOp() {
            UUID userId = UUID.randomUUID();
            Wallets w = wallet(userId, "100.00");
            when(walletRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(w));
            when(mutationRepository.existsByWalletIdAndReferenceIdAndType(w.getId(), "ref-1", Type.CREDIT)).thenReturn(true);

            BalanceResponse res = service.credit(new CreditDebitRequest(userId, new BigDecimal("50.00"), "ref-1", "topup"));

            assertThat(res.balance()).isEqualByComparingTo("100.00");
            verify(mutationRepository, never()).save(any());
            verify(walletRepository, never()).save(any());
        }

        @Test
        void unknownWalletIsNotFound() {
            UUID userId = UUID.randomUUID();
            when(walletRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.credit(new CreditDebitRequest(userId, BigDecimal.TEN, "r", "d")))
                    .isInstanceOf(WalletException.WalletNotFoundException.class);
        }
    }

    @Test
    void debitRejectsInsufficientBalance() {
        UUID userId = UUID.randomUUID();
        when(walletRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(wallet(userId, "10.00")));

        assertThatThrownBy(() -> service.debit(new CreditDebitRequest(userId, new BigDecimal("10.01"), "r", "d")))
                .isInstanceOf(WalletException.InsufficientBalanceException.class);
        verify(mutationRepository, never()).save(any());
    }

    @Nested
    class AtomicTransfer {
        // Fixed ids so the lock order is deterministic: LOW < HIGH
        final UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final UUID high = UUID.fromString("00000000-0000-0000-0000-000000000002");

        @Test
        void movesMoneyWithTwoLedgerLegs() {
            Wallets from = wallet(high, "100.00");
            Wallets to = wallet(low, "5.00");
            when(walletRepository.findByUserIdForUpdate(low)).thenReturn(Optional.of(to));
            when(walletRepository.findByUserIdForUpdate(high)).thenReturn(Optional.of(from));

            service.atomicTransfer(new AtomicTransferRequest(high, low, new BigDecimal("40.00"), "txn-1", "Lunch"));

            assertThat(from.getBalance()).isEqualByComparingTo("60.00");
            assertThat(to.getBalance()).isEqualByComparingTo("45.00");
            ArgumentCaptor<Mutations> m = ArgumentCaptor.forClass(Mutations.class);
            verify(mutationRepository, times(2)).save(m.capture());
            List<Mutations> legs = m.getAllValues();
            assertThat(legs).extracting(Mutations::getType).containsExactly(Type.DEBIT, Type.CREDIT);
            assertThat(legs).extracting(Mutations::getReferenceId).containsOnly("txn-1");
        }

        @Test
        void locksWalletsInAscendingUserIdOrderToAvoidDeadlock() {
            when(walletRepository.findByUserIdForUpdate(low)).thenReturn(Optional.of(wallet(low, "100.00")));
            when(walletRepository.findByUserIdForUpdate(high)).thenReturn(Optional.of(wallet(high, "100.00")));

            // high -> low and low -> high must both lock LOW first
            service.atomicTransfer(new AtomicTransferRequest(high, low, BigDecimal.ONE, "a", "x"));
            service.atomicTransfer(new AtomicTransferRequest(low, high, BigDecimal.ONE, "b", "x"));

            InOrder order = inOrder(walletRepository);
            order.verify(walletRepository).findByUserIdForUpdate(low);
            order.verify(walletRepository).findByUserIdForUpdate(high);
            order.verify(walletRepository).findByUserIdForUpdate(low);
            order.verify(walletRepository).findByUserIdForUpdate(high);
        }

        @Test
        void duplicateReferenceIsNoOp() {
            Wallets from = wallet(high, "100.00");
            when(walletRepository.findByUserIdForUpdate(low)).thenReturn(Optional.of(wallet(low, "0.00")));
            when(walletRepository.findByUserIdForUpdate(high)).thenReturn(Optional.of(from));
            when(mutationRepository.existsByWalletIdAndReferenceIdAndType(from.getId(), "txn-1", Type.DEBIT)).thenReturn(true);

            service.atomicTransfer(new AtomicTransferRequest(high, low, BigDecimal.TEN, "txn-1", "x"));

            assertThat(from.getBalance()).isEqualByComparingTo("100.00");
            verify(mutationRepository, never()).save(any());
        }

        @Test
        void insufficientBalanceChangesNothing() {
            Wallets from = wallet(high, "5.00");
            when(walletRepository.findByUserIdForUpdate(low)).thenReturn(Optional.of(wallet(low, "0.00")));
            when(walletRepository.findByUserIdForUpdate(high)).thenReturn(Optional.of(from));

            assertThatThrownBy(() -> service.atomicTransfer(new AtomicTransferRequest(high, low, BigDecimal.TEN, "t", "x")))
                    .isInstanceOf(WalletException.InsufficientBalanceException.class);
            assertThat(from.getBalance()).isEqualByComparingTo("5.00");
        }

        @Test
        void selfTransferIsRejected() {
            assertThatThrownBy(() -> service.atomicTransfer(new AtomicTransferRequest(low, low, BigDecimal.TEN, "t", "x")))
                    .isInstanceOf(WalletException.InvalidTransferException.class);
            verifyNoInteractions(walletRepository);
        }
    }

    @Test
    void getBalanceLazilyCreatesMissingWallet() {
        UUID userId = UUID.randomUUID();
        when(walletRepository.findByUserId(userId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(wallet(userId, "0.00")));

        BalanceResponse res = service.getBalance(userId);

        verify(walletRepository).insertIfAbsent(userId);
        assertThat(res.balance()).isEqualByComparingTo("0.00");
    }
}
