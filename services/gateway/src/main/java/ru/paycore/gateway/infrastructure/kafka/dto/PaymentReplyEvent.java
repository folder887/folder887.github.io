package ru.paycore.gateway.infrastructure.kafka.dto;

import java.time.Instant;

/**
 * Reply event from Processing Core — mirrors processing's PaymentReplyEvent.
 */
public record PaymentReplyEvent(
    String correlationId,
    String idempotencyKey,
    String status,
    String authCode,
    String rrn,
    String declineCode,
    long amountKopecks,
    String currencyCode,
    Instant processedAt
) {}
