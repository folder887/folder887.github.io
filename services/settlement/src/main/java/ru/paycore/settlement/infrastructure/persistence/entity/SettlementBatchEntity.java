package ru.paycore.settlement.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A settlement batch groups captured transactions per merchant
 * for netting and single payout.
 *
 * Netting formula: net_amount = gross_amount - fee_amount
 * All monetary values in kopecks (long). Never float.
 */
@Entity
@Table(name = "settlement_batches")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementBatchEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SettlementBatchStatus status;

    /** Number of transactions in this batch. */
    @Column(name = "transaction_count", nullable = false)
    private int transactionCount;

    /** Total gross amount before fees (kopecks). */
    @Column(name = "gross_amount", nullable = false)
    private long grossAmount;

    /** Total fee deducted (kopecks). */
    @Column(name = "fee_amount", nullable = false)
    private long feeAmount;

    /** Net amount to be paid out to merchant (kopecks). */
    @Column(name = "net_amount", nullable = false)
    private long netAmount;

    @Column(name = "period_from", nullable = false)
    private Instant periodFrom;

    @Column(name = "period_to", nullable = false)
    private Instant periodTo;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "error_reason")
    private String errorMessage;

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = updatedAt = Instant.now();
        if (transactionCount == 0) transactionCount = 0;
        if (grossAmount == 0) grossAmount = 0;
        if (feeAmount == 0) feeAmount = 0;
        if (netAmount == 0) netAmount = 0;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
