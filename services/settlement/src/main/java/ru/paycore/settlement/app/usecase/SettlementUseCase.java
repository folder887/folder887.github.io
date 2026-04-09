package ru.paycore.settlement.app.usecase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.paycore.settlement.infrastructure.persistence.entity.SettlementBatchEntity;
import ru.paycore.settlement.infrastructure.persistence.entity.SettlementBatchStatus;
import ru.paycore.settlement.infrastructure.persistence.repository.SettlementBatchRepository;
import ru.paycore.settlement.infrastructure.persistence.repository.SettlementTransactionRepository;
import ru.paycore.settlement.infrastructure.kafka.SettledEventPublisher;
import ru.paycore.settlement.infrastructure.kafka.dto.CapturedEvent;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Settlement Engine — accumulates captured transactions and settles them in batches.
 *
 * Settlement flow:
 *   1. Kafka consumer receives CAPTURED event
 *   2. Transaction added to pending batch for merchant
 *   3. Netting job runs every hour (or on-demand)
 *   4. Batch: gross_amount - fee_amount = net_amount → merchant account credited
 *   5. Journal entries written (double-entry: transit → merchant)
 *   6. SETTLED event published to Kafka
 *
 * SLO: settlement latency < 2 hours.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementUseCase {

    private final SettlementBatchRepository batchRepository;
    private final SettlementTransactionRepository settlementTxRepository;
    private final SettledEventPublisher settledEventPublisher;

    /**
     * Add a captured transaction to the merchant's current open batch.
     * If no open batch exists, creates one.
     */
    @Transactional
    public void addToBatch(CapturedEvent event) {
        UUID merchantId = event.merchantId();
        long grossAmount = event.amountKopecks();
        long feeAmount = calculateFee(grossAmount, event.feeRateBps());
        long netAmount = grossAmount - feeAmount;

        SettlementBatchEntity batch = batchRepository
            .findOpenBatchForMerchant(merchantId)
            .orElseGet(() -> createNewBatch(merchantId));

        // Atomically accumulate — no race condition since batch is locked
        batchRepository.accumulateAmounts(batch.getId(), 1, grossAmount, feeAmount, netAmount);
        settlementTxRepository.insert(batch.getId(), event.transactionId(), grossAmount, feeAmount);

        log.info("action=added_to_batch merchant_id={} transaction_id={} batch_id={} net={}",
            merchantId, event.transactionId(), batch.getId(), netAmount);
    }

    /**
     * Process all pending batches older than 1 hour.
     * Called by scheduled job every 30 minutes.
     *
     * Netting: sum all transactions per merchant → single payout.
     */
    @Transactional
    public void processPendingBatches() {
        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
        List<SettlementBatchEntity> batches = batchRepository.findPendingOlderThan(cutoff);

        log.info("action=settlement_run batch_count={}", batches.size());

        for (SettlementBatchEntity batch : batches) {
            settleBatch(batch);
        }
    }

    @Transactional
    public void settleBatch(SettlementBatchEntity batch) {
        try {
            batchRepository.updateStatus(batch.getId(), SettlementBatchStatus.PROCESSING);

            // Credit merchant account (transit → merchant)
            // In production: initiate bank transfer via SWIFT/SPFS/СБП
            long netAmount = batch.getNetAmount();

            log.info("action=settle_batch batch_id={} merchant_id={} net_amount={}",
                batch.getId(), batch.getMerchantId(), netAmount);

            batchRepository.markSettled(batch.getId(), Instant.now());

            // Publish SETTLED event for each transaction in batch
            settledEventPublisher.publishBatchSettled(batch);

            log.info("action=batch_settled batch_id={} transaction_count={} net_kopecks={}",
                batch.getId(), batch.getTransactionCount(), netAmount);

        } catch (Exception e) {
            log.error("action=batch_settlement_failed batch_id={}", batch.getId(), e);
            batchRepository.markFailed(batch.getId(), e.getMessage());
        }
    }

    private long calculateFee(long grossKopecks, int feeRateBps) {
        // Fee calculation: integer arithmetic only, no float
        // feeRateBps is basis points (100 bps = 1%)
        // fee = gross * feeRateBps / 10000
        // Using long arithmetic to prevent overflow
        return grossKopecks * feeRateBps / 10_000L;
    }

    private SettlementBatchEntity createNewBatch(UUID merchantId) {
        Instant now = Instant.now();
        SettlementBatchEntity batch = SettlementBatchEntity.builder()
            .id(UUID.randomUUID())
            .merchantId(merchantId)
            .status(SettlementBatchStatus.PENDING)
            .periodFrom(now)
            .periodTo(now.plus(1, ChronoUnit.HOURS))
            .build();
        return batchRepository.save(batch);
    }
}
