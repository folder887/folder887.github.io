package ru.paycore.processing.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import ru.paycore.processing.domain.model.TransactionState;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent transaction record.
 *
 * Partitioned by created_at (monthly) — see V1__initial_schema.sql.
 * All monetary amounts in kopecks (long). Never float/double.
 */
@Entity
@Table(name = "transactions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    /** Transaction amount in kopecks. */
    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TransactionState status;

    @Column(name = "mcc", columnDefinition = "char(4)")
    private String mcc;

    /** 6-digit authorization code. Set when AUTHORIZED. */
    @Column(name = "auth_code", length = 6)
    private String authCode;

    /** Retrieval Reference Number — 12 digits. */
    @Column(name = "rrn", length = 12)
    private String rrn;

    /** Decline reason code (INSUFFICIENT_FUNDS, FRAUD_SUSPECTED, etc.). */
    @Column(name = "decline_reason")
    private String declineReason;

    /** Which acquirer processed this: yookassa / tinkoff / internal. */
    @Column(name = "acquirer_name", length = 32)
    private String acquirerName;

    /** Acquirer's own transaction ID — used for reconciliation and refunds. */
    @Column(name = "acquirer_tx_id", length = 128)
    private String acquirerTxId;

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
