package com.gpay.payment.repository;

import com.gpay.payment.entity.TopupRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TopUpRequestRepository extends JpaRepository<TopupRequest, UUID> {
    Optional<TopupRequest> findByGatewayRef(String gatewayRef);

    /** Fallback for webhooks that arrive before gateway_ref was stored. */
    Optional<TopupRequest> findByTransactionId(UUID transactionId);

    /** No-op if the webhook already stored the reference first. */
    @Transactional
    @Modifying
    @Query("UPDATE TopupRequest t SET t.gatewayRef = :gatewayRef WHERE t.id = :id AND t.gatewayRef IS NULL")
    int assignGatewayRefIfAbsent(UUID id, String gatewayRef);
}
