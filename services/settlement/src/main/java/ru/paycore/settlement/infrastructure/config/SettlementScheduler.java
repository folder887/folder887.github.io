package ru.paycore.settlement.infrastructure.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.paycore.settlement.app.usecase.SettlementUseCase;

/**
 * Scheduled settlement job.
 * Runs every 30 minutes to process batches older than 1 hour.
 * SLO: settlement latency < 2 hours.
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class SettlementScheduler {

    private final SettlementUseCase settlementUseCase;

    @Scheduled(fixedDelayString = "${paycore.settlement.batch-interval-ms:1800000}")
    public void runSettlement() {
        log.info("action=settlement_job_start");
        try {
            settlementUseCase.processPendingBatches();
            log.info("action=settlement_job_complete");
        } catch (Exception e) {
            log.error("action=settlement_job_error", e);
        }
    }
}
