package ru.paycore.settlement.infrastructure.kafka.dto;

import java.time.Instant;

/**
 * Event published when a settlement batch is successfully settled.
 * All monetary values in kopecks (long). Never float.
 */
public record SettledEvent(
    String batchId,
    String merchantId,
    int transactionCount,
    long grossAmountKopecks,
    long feeAmountKopecks,
    long netAmountKopecks,
    Instant periodFrom,
    Instant periodTo,
    Instant settledAt
) {}
