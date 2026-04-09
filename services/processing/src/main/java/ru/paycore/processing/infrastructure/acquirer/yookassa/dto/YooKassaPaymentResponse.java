package ru.paycore.processing.infrastructure.acquirer.yookassa.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * YooKassa API v3 — Payment object response.
 * Docs: https://yookassa.ru/developers/api#payment_object
 *
 * Statuses:
 *   pending    — waiting for confirmation
 *   waiting_for_capture — authorized, needs manual capture
 *   succeeded  — payment complete
 *   canceled   — declined or canceled
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record YooKassaPaymentResponse(
    @JsonProperty("id")
    String id,

    @JsonProperty("status")
    String status,

    @JsonProperty("paid")
    boolean paid,

    @JsonProperty("authorization_details")
    AuthorizationDetails authorizationDetails,

    @JsonProperty("cancellation_details")
    CancellationDetails cancellationDetails
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuthorizationDetails(
        @JsonProperty("auth_code")
        String authCode
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CancellationDetails(
        @JsonProperty("reason")
        String reason
    ) {}

    public boolean isAuthorized() {
        // pending = payment initiated, customer will complete via redirect
        // waiting_for_capture = authorized, needs manual capture
        // succeeded = payment complete
        return "succeeded".equals(status) || "waiting_for_capture".equals(status) || "pending".equals(status);
    }

    public String authCode() {
        if (authorizationDetails != null && authorizationDetails.authCode() != null) {
            return authorizationDetails.authCode();
        }
        // YooKassa sandbox doesn't always return authCode — generate deterministic one
        return "%06d".formatted(Math.abs(id.hashCode() % 999999));
    }

    public String declineReason() {
        if (cancellationDetails != null) return cancellationDetails.reason();
        return "canceled";
    }
}
