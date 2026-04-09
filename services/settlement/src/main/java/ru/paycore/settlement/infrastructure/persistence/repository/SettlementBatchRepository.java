package ru.paycore.settlement.infrastructure.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.paycore.settlement.infrastructure.persistence.entity.SettlementBatchEntity;
import ru.paycore.settlement.infrastructure.persistence.entity.SettlementBatchStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementBatchRepository extends JpaRepository<SettlementBatchEntity, UUID> {

    @Query(value = """
        SELECT b.* FROM settlement_batches b
        WHERE b.merchant_id = :merchantId
          AND b.status = 'PENDING'
        ORDER BY b.created_at DESC
        LIMIT 1
        FOR UPDATE NOWAIT
        """, nativeQuery = true)
    Optional<SettlementBatchEntity> findOpenBatchForMerchant(@Param("merchantId") UUID merchantId);

    @Query(value = """
        SELECT b.* FROM settlement_batches b
        WHERE b.status = 'PENDING'
          AND b.created_at < :cutoff
        ORDER BY b.created_at ASC
        """, nativeQuery = true)
    List<SettlementBatchEntity> findPendingOlderThan(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query(value = """
        UPDATE settlement_batches
        SET transaction_count = transaction_count + :txCount,
            gross_amount = gross_amount + :grossAmount,
            fee_amount   = fee_amount   + :feeAmount,
            net_amount   = net_amount   + :netAmount,
            updated_at   = now()
        WHERE id = :batchId
        """, nativeQuery = true)
    void accumulateAmounts(
        @Param("batchId") UUID batchId,
        @Param("txCount") int txCount,
        @Param("grossAmount") long grossAmount,
        @Param("feeAmount") long feeAmount,
        @Param("netAmount") long netAmount
    );

    @Modifying
    @Query(value = """
        UPDATE settlement_batches SET status = :status, updated_at = now()
        WHERE id = :batchId
        """, nativeQuery = true)
    void updateStatus(@Param("batchId") UUID batchId, @Param("status") String status);

    @Modifying
    @Query(value = """
        UPDATE settlement_batches
        SET status = 'SETTLED', settled_at = :settledAt, updated_at = now()
        WHERE id = :batchId
        """, nativeQuery = true)
    void markSettled(@Param("batchId") UUID batchId, @Param("settledAt") Instant settledAt);

    @Modifying
    @Query(value = """
        UPDATE settlement_batches
        SET status = 'FAILED', error_reason = :errorMessage, updated_at = now()
        WHERE id = :batchId
        """, nativeQuery = true)
    void markFailed(@Param("batchId") UUID batchId, @Param("errorMessage") String errorMessage);

    default void updateStatus(UUID batchId, SettlementBatchStatus status) {
        updateStatus(batchId, status.name());
    }
}
