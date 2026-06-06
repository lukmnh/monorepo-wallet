package com.gpay.auth.repository;

import com.gpay.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("UPDATE RefreshToken r SET r.revoked = true WHERE r.user.id = :userId")
    void revokeAllByUserId(UUID userId);

    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.expiresAt < :now OR r.revoked = true")
    void deleteExpiredAndRevoked(LocalDateTime now);
}
