package ru.paycore.processing.infrastructure.acquirer.tinkoff.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Tinkoff Kassa API — Init response.
 *
 * Statuses:
 *   NEW          — payment created
 *   FORM_SHOWED  — payment form shown to user
 *   AUTHORIZED   — authorized, not yet captured
 *   CONFIRMED    — captured
 *   REJECTED     — declined
 *   CANCELLED    — cancelled
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TinkoffInitResponse(
    @JsonProperty("Success")
    boolean success,

    @JsonProperty("ErrorCode")
    String errorCode,

    @JsonProperty("Message")
    String message,

    @JsonProperty("PaymentId")
    String paymentId,

    @JsonProperty("Status")
    String status,

    @JsonProperty("PaymentURL")
    String paymentUrl
) {
    public boolean isAuthorized() {
        return success && ("AUTHORIZED".equals(status) || "CONFIRMED".equals(status) || "NEW".equals(status));
    }

    public String declineCode() {
        if (errorCode != null && !"0".equals(errorCode)) return errorCode;
        return status;
    }
}
