package com.gpay.payment.repository;

import com.gpay.payment.entity.TransferRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransferRequestRepository extends JpaRepository<TransferRequest, UUID> {
    Optional<TransferRequest> findByTransactionId(UUID transactionId);
}
