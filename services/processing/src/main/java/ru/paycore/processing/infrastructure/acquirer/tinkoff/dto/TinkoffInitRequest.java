package ru.paycore.processing.infrastructure.acquirer.tinkoff.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Tinkoff Kassa API — Init request.
 * Docs: https://www.tinkoff.ru/kassa/develop/api/payments/init-request/
 *
 * All monetary values in kopecks (Tinkoff calls it "Amount" in kopecks).
 */
public record TinkoffInitRequest(
    @JsonProperty("TerminalKey")
    String terminalKey,

    @JsonProperty("Amount")
    long amount,            // in kopecks

    @JsonProperty("OrderId")
    String orderId,

    @JsonProperty("Description")
    String description,

    @JsonProperty("Language")
    String language,

    @JsonProperty("Token")
    String token            // HMAC-SHA256 signature
) {}
