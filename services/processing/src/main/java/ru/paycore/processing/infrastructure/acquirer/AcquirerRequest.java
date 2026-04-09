package ru.paycore.processing.infrastructure.acquirer;

import java.util.UUID;

/**
 * Unified payment authorization request for any acquirer.
 * Created from AuthorizeCommand by AcquirerRoutingService.
 */
public record AcquirerRequest(
    /** Our internal transaction ID — used as idempotency key with acquirer. */
    UUID correlationId,

    /** Merchant ID in our system. */
    UUID merchantId,

    /** Amount in kopecks. Never float. */
    long amountKopecks,

    /** ISO 4217 numeric currency code: 643 = RUB, 840 = USD. */
    String currencyCode,

    /** First 6 digits of PAN (BIN). Used for routing, never stored. */
    String panFirstSix,

    /** ISO 18245 Merchant Category Code. */
    String mcc,

    /** PURCHASE, REFUND, etc. */
    String transactionType,

    /** Human-readable payment description sent to acquirer. */
    String description
) {
    /** Amount in rubles with 2 decimal places, as required by most Russian acquirer APIs. */
    public String amountRubles() {
        long rubles = amountKopecks / 100;
        long kopecks = amountKopecks % 100;
        return "%d.%02d".formatted(rubles, kopecks);
    }
}
