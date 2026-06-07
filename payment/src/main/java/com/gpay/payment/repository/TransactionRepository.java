package com.gpay.payment.repository;

import com.gpay.payment.entity.Transactions;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transactions, UUID> {
    @Query("SELECT t FROM Transactions t WHERE t.status = 'PENDING' AND t.createdAt < :expiresBefore AND t.type = 'TOPUP'")
    List<Transactions> findExpiredPendingTopups(LocalDateTime expiresBefore);
}
