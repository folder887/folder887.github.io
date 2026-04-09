package ru.paycore.gateway.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Domain model of an incoming payment authorization request.
 * Immutable value object — created at gateway entry point.
 */
public record PaymentRequest(
    UUID id,
    String idempotencyKey,
    UUID merchantId,
    String panHash,        // SHA-256(pan || pepper) — PAN never stored
    String panLastFour,
    String panFirstSix,
    short expiryMonth,
    short expiryYear,
    String network,        // VISA, MASTERCARD, MIR
    Money amount,
    String mcc,
    TransactionType transactionType,
    Map<String, String> metadata,
    Instant receivedAt
) {
    public PaymentRequest {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key must not be blank");
        }
        if (idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("Idempotency key too long (max 128)");
        }
        if (amount.isZero()) {
            throw new IllegalArgumentException("Payment amount must be > 0");
        }
        if (!mcc.matches("^[0-9]{4}$")) {
            throw new IllegalArgumentException("Invalid MCC: " + mcc);
        }
    }
}
