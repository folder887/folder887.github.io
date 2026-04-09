package ru.paycore.processing.infrastructure.kafka.dto;

import java.time.Instant;

/**
 * Reply event sent from Processing Core to Gateway.
 * Consumed by Gateway's PaymentReplyListener.
 */
public record PaymentReplyEvent(
    String correlationId,    // = transactionId (UUID as string)
    String idempotencyKey,
    String status,           // AUTHORIZED | DECLINED
    String authCode,         // 6-digit code, null if declined
    String rrn,              // 12-digit retrieval reference, null if declined
    String declineCode,      // INSUFFICIENT_FUNDS | FRAUD_SUSPECTED | ..., null if authorized
    long amountKopecks,
    String currencyCode,
    Instant processedAt
) {}
