package ru.paycore.processing.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Double-entry journal (ledger).
 *
 * APPEND-ONLY — no UPDATE or DELETE ever (enforced by DB trigger).
 * Invariant: SUM(debit) = SUM(credit) across all entries per transaction.
 *
 * All monetary values in kopecks (long).
 */
@Entity
@Table(name = "journal_entries")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JournalEntryEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    /** Debit amount in kopecks. Exactly one of debit/credit is non-zero. */
    @Column(name = "debit", nullable = false)
    private long debit;

    /** Credit amount in kopecks. Exactly one of debit/credit is non-zero. */
    @Column(name = "credit", nullable = false)
    private long credit;

    /** Entry type: AUTHORIZATION, CAPTURE, REVERSAL, SETTLEMENT. */
    @Column(name = "entry_type", nullable = false)
    private String entryType;

    /** Balance snapshot on the account after this entry. */
    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }
}
