package ru.paycore.gateway.infrastructure.kafka.dto;

import java.time.Instant;

public record ReversalCommand(
    String correlationId,
    String transactionId,
    String idempotencyKey,
    Instant sentAt
) {
    public static ReversalCommand of(String transactionId, String idempotencyKey) {
        return new ReversalCommand(idempotencyKey, transactionId, idempotencyKey, Instant.now());
    }
}
