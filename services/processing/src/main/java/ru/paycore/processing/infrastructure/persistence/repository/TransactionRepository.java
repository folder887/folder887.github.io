package ru.paycore.processing.infrastructure.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.paycore.processing.domain.model.TransactionState;
import ru.paycore.processing.infrastructure.persistence.entity.TransactionEntity;

import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<TransactionEntity, UUID> {

    Optional<TransactionEntity> findByIdempotencyKey(String idempotencyKey);

    /**
     * Atomic status transition — only updates if current status matches expected.
     * Returns 1 on success, 0 if concurrently modified.
     */
    @Modifying
    @Query(value = """
        UPDATE transactions
        SET status = :newStatus, updated_at = now()
        WHERE id = :id AND status = :expectedStatus
        """, nativeQuery = true)
    int transitionStatus(
        @Param("id") UUID id,
        @Param("expectedStatus") String expectedStatus,
        @Param("newStatus") String newStatus
    );
}
