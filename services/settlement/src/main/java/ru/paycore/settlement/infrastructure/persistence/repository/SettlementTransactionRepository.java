package ru.paycore.settlement.infrastructure.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.paycore.settlement.infrastructure.persistence.entity.SettlementTransactionEntity;

import java.util.UUID;

public interface SettlementTransactionRepository extends JpaRepository<SettlementTransactionEntity, SettlementTransactionEntity.PK> {

    @Modifying
    @Query(value = """
        INSERT INTO settlement_transactions (settlement_batch_id, transaction_id, amount, fee_amount)
        VALUES (:batchId, :transactionId, :grossAmount, :feeAmount)
        """, nativeQuery = true)
    void insert(
        @Param("batchId") UUID batchId,
        @Param("transactionId") UUID transactionId,
        @Param("grossAmount") long grossAmount,
        @Param("feeAmount") long feeAmount
    );
}
