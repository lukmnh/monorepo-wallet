package com.gpay.wallets.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "wallets", schema = "wallet")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Wallets {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
