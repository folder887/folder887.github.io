package ru.paycore.processing.infrastructure.kafka.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Kafka command consumed from "payment.commands" topic.
 * Produced by Gateway. Contains NO raw PAN — only pan_hash.
 */
public record AuthorizeCommand(
    UUID correlationId,
    String idempotencyKey,
    UUID merchantId,
    String panHash,
    String panLastFour,
    String panFirstSix,
    short expiryMonth,
    short expiryYear,
    String network,
    long amountKopecks,
    String currencyCode,
    String mcc,
    String transactionType,
    Map<String, String> metadata,
    Instant sentAt
) {}
