package ru.paycore.settlement.infrastructure.kafka.dto;

import java.time.Instant;
import java.util.UUID;

public record CapturedEvent(
    UUID transactionId,
    UUID merchantId,
    long amountKopecks,   // BIGINT — kopecks only, never float
    String currencyCode,
    int feeRateBps,       // merchant fee rate in basis points
    String mcc,
    Instant capturedAt
) {}
