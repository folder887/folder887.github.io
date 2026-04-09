package ru.paycore.processing.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Merchant/customer account.
 *
 * Invariants enforced by DB constraints:
 *   - balance >= 0  (accounts_balance_check)
 *   - reserved >= 0 (accounts_reserved_check)
 *
 * All monetary fields are in kopecks (BIGINT). Never use float.
 */
@Entity
@Table(name = "accounts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "account_type", nullable = false)
    private String accountType;

    /** Available balance in kopecks. Never negative. */
    @Column(name = "balance", nullable = false)
    private long balance;

    /** Amount reserved (authorized but not yet captured). */
    @Column(name = "reserved", nullable = false)
    private long reserved;

    @Column(name = "currency", nullable = false)
    private String currencyCode;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    /** Optimistic locking version for concurrent modification detection. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
