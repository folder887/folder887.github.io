package ru.paycore.processing.infrastructure.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.paycore.processing.infrastructure.persistence.entity.AccountEntity;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {

    /**
     * Lock account row for update, NOWAIT — fail immediately if locked.
     * This is the critical section for balance reservation.
     *
     * EXPLAIN ANALYZE must show Index Scan on accounts_pkey — no SeqScan.
     */
    @Query(value = """
        SELECT a.* FROM accounts a
        WHERE a.merchant_id = :merchantId
          AND a.account_type = 'MERCHANT'
          AND a.is_active = true
        FOR UPDATE NOWAIT
        """, nativeQuery = true)
    Optional<AccountEntity> findByMerchantIdForUpdate(@Param("merchantId") UUID merchantId);

    /**
     * Atomically reserve balance (deduct from available, add to reserved).
     * Uses optimistic locking via version check — returns 0 if concurrent modification detected.
     *
     * INVARIANT: balance >= 0 enforced by DB constraint accounts_balance_check.
     */
    @Modifying
    @Query(value = """
        UPDATE accounts
        SET balance  = balance  - :amount,
            reserved = reserved + :amount,
            updated_at = now()
        WHERE id = :accountId
          AND balance >= :amount
          AND version = :expectedVersion
        """, nativeQuery = true)
    int reserveBalance(
        @Param("accountId") UUID accountId,
        @Param("amount") long amount,
        @Param("expectedVersion") long expectedVersion
    );

    /**
     * Capture: move from reserved to settled (for merchant payout).
     */
    @Modifying
    @Query(value = """
        UPDATE accounts
        SET reserved = reserved - :amount,
            updated_at = now()
        WHERE id = :accountId
          AND reserved >= :amount
        """, nativeQuery = true)
    int captureReservation(@Param("accountId") UUID accountId, @Param("amount") long amount);

    /**
     * Release reservation on reversal.
     */
    @Modifying
    @Query(value = """
        UPDATE accounts
        SET balance  = balance  + :amount,
            reserved = reserved - :amount,
            updated_at = now()
        WHERE id = :accountId
          AND reserved >= :amount
        """, nativeQuery = true)
    int releaseReservation(@Param("accountId") UUID accountId, @Param("amount") long amount);
}
