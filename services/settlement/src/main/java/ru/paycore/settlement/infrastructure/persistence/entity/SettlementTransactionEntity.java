package ru.paycore.settlement.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.util.UUID;

/**
 * Links a captured transaction to its settlement batch.
 * Append-only — never updated after insert.
 * PK: (settlement_batch_id, transaction_id)
 */
@Entity
@Table(name = "settlement_transactions")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@IdClass(SettlementTransactionEntity.PK.class)
public class SettlementTransactionEntity {

    @Id
    @Column(name = "settlement_batch_id", updatable = false, nullable = false)
    private UUID batchId;

    @Id
    @Column(name = "transaction_id", updatable = false, nullable = false)
    private UUID transactionId;

    /** Gross transaction amount in kopecks. */
    @Column(name = "amount", nullable = false)
    private long grossAmount;

    /** Fee charged for this transaction in kopecks. */
    @Column(name = "fee_amount", nullable = false)
    private long feeAmount;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PK implements Serializable {
        private UUID batchId;
        private UUID transactionId;
    }
}
