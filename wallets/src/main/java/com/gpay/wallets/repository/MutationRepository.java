package com.gpay.wallets.repository;

import com.gpay.wallets.entity.Mutations;
import com.gpay.wallets.entity.Wallets;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MutationRepository extends JpaRepository<Mutations, UUID> {
    boolean existsByReferenceId(String referenceId);
    Page<Mutations> findByWalletIdOrderByCreatedAtDesc(UUID walletId, Pageable pageable);
}
