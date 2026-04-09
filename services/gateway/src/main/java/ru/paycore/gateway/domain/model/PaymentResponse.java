package ru.paycore.gateway.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Result of a payment authorization attempt.
 */
public record PaymentResponse(
    UUID transactionId,
    String idempotencyKey,
    TransactionStatus status,
    Money amount,
    String authCode,          // null if declined
    String rrn,               // Retrieval Reference Number
    String declineReason,     // null if approved
    Instant processedAt
) {
    public boolean isApproved() {
        return status == TransactionStatus.AUTHORIZED || status == TransactionStatus.CAPTURED;
    }
}
