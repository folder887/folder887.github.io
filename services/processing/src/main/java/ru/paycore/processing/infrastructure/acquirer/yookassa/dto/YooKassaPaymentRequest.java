package ru.paycore.processing.infrastructure.acquirer.yookassa.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * YooKassa API v3 — Create Payment request.
 * Docs: https://yookassa.ru/developers/api#create_payment
 */
public record YooKassaPaymentRequest(
    @JsonProperty("amount")
    Amount amount,

    @JsonProperty("capture")
    boolean capture,

    @JsonProperty("description")
    String description,

    @JsonProperty("metadata")
    java.util.Map<String, String> metadata,

    @JsonProperty("confirmation")
    Confirmation confirmation
) {
    public record Amount(
        @JsonProperty("value")   String value,
        @JsonProperty("currency") String currency
    ) {}

    /**
     * Confirmation scenario — redirect sends the customer to YooKassa-hosted page.
     * For API-only integrations (e.g. via payment_token or payment_method_id),
     * this field can be omitted, but for our gateway flow we use redirect.
     */
    public record Confirmation(
        @JsonProperty("type")       String type,
        @JsonProperty("return_url") String returnUrl
    ) {}

    /** RUB numeric code 643 → YooKassa expects "RUB" string. */
    public static String toCurrencyCode(String numericCode) {
        return switch (numericCode) {
            case "643" -> "RUB";
            case "840" -> "USD";
            case "978" -> "EUR";
            default    -> "RUB";
        };
    }
}
