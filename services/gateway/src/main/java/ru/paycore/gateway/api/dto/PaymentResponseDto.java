package ru.paycore.gateway.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Payment authorization result")
public record PaymentResponseDto(
    @Schema(description = "Transaction UUID") UUID transactionId,
    @Schema(description = "Echoed idempotency key") String idempotencyKey,
    @Schema(description = "Transaction status") String status,
    @Schema(description = "Amount in kopecks") long amountKopecks,
    @Schema(description = "ISO 4217 currency code") String currencyCode,
    @Schema(description = "6-digit authorization code (null if declined)") String authCode,
    @Schema(description = "Retrieval Reference Number") String rrn,
    @Schema(description = "Decline reason code (null if approved)") String declineReason,
    @Schema(description = "Processing timestamp") Instant processedAt
) {}
