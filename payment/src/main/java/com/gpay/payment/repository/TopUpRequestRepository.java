package com.gpay.payment.repository;

import com.gpay.payment.entity.TopupRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TopUpRequestRepository extends JpaRepository<TopupRequest, UUID> {
    Optional<TopupRequest> findByGatewayRef(String gatewayRef);

}
