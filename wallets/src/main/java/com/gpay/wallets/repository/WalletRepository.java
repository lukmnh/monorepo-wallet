package com.gpay.wallets.repository;

import com.gpay.wallets.entity.Wallets;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WalletRepository extends JpaRepository<Wallets, UUID> {

    Optional<Wallets> findByUserId(UUID userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallets w WHERE w.userId = :userId")
    Optional<Wallets> findByUserIdForUpdate(UUID userId);
}
