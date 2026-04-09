package ru.paycore.gateway.infrastructure.kafka.dto;

import ru.paycore.gateway.domain.model.PaymentRequest;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Kafka command DTO — sent from Gateway to Processing Core.
 * Contains NO raw PAN — only pan_hash.
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
) {
    public static AuthorizeCommand from(PaymentRequest req) {
        return new AuthorizeCommand(
            req.id(),
            req.idempotencyKey(),
            req.merchantId(),
            req.panHash(),
            req.panLastFour(),
            req.panFirstSix(),
            req.expiryMonth(),
            req.expiryYear(),
            req.network(),
            req.amount().kopecks(),
            req.amount().currency().isoCode(),
            req.mcc(),
            req.transactionType().name(),
            req.metadata(),
            Instant.now()
        );
    }
}
