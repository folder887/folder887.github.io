package ru.paycore.processing.infrastructure.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.paycore.processing.infrastructure.persistence.entity.JournalEntryEntity;

import java.util.UUID;

/**
 * Journal entries are APPEND-ONLY.
 * DB trigger prevents UPDATE and DELETE.
 *
 * Double-entry invariant: for each transaction,
 *   SUM(debit) = SUM(credit) across all journal_entries.
 */
public interface JournalEntryRepository extends JpaRepository<JournalEntryEntity, UUID> {

    /**
     * Insert a balanced pair of journal entries (debit + credit) in one statement.
     * Native SQL for performance — no ORM overhead on the critical path.
     *
     * INVARIANT: debitAmount = creditAmount (both sides of the ledger).
     */
    @Modifying
    @Query(value = """
        INSERT INTO journal_entries
            (id, transaction_id, entry_type, account_id, debit, credit, balance_after, description, created_at)
        VALUES
            -- Debit side (customer account: balance decreases)
            (:debitId,  :transactionId, CAST(:entryType AS journal_entry_type),
             :debitAccountId,  :amount, 0, :balanceAfter, :description, now()),
            -- Credit side (transit account: balance increases)
            (:creditId, :transactionId, CAST(:entryType AS journal_entry_type),
             :creditAccountId, 0, :amount, -1, :description, now())
        """, nativeQuery = true)
    void insertPair(
        @Param("debitId") UUID debitId,
        @Param("creditId") UUID creditId,
        @Param("transactionId") UUID transactionId,
        @Param("debitAccountId") UUID debitAccountId,
        @Param("creditAccountId") UUID creditAccountId,
        @Param("amount") long amount,
        @Param("entryType") String entryType,
        @Param("description") String description,
        @Param("balanceAfter") long balanceAfter
    );

    /**
     * Verify double-entry invariant for a transaction.
     * Used in audits and integration tests.
     *
     * @return 0 if balanced, non-zero if violated (should never happen)
     */
    @Query(value = """
        SELECT SUM(debit) - SUM(credit)
        FROM journal_entries
        WHERE transaction_id = :transactionId
        """, nativeQuery = true)
    Long checkBalance(@Param("transactionId") UUID transactionId);
}
