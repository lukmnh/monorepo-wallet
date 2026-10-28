package com.gpay.wallets.repository;

import com.gpay.wallets.constant.Type;
import com.gpay.wallets.entity.Mutations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MutationRepository extends JpaRepository<Mutations, UUID> {
    // Matches UNIQUE (wallet_id, reference_id, type)
    boolean existsByWalletIdAndReferenceIdAndType(UUID walletId, String referenceId, Type type);
    Page<Mutations> findByWalletIdOrderByCreatedAtDesc(UUID walletId, Pageable pageable);
}
