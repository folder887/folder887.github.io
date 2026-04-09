package ru.paycore.gateway.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.util.Map;

/**
 * Payment authorization request DTO.
 * PAN is accepted here (HTTPS only) and immediately hashed — never stored.
 */
@Schema(description = "Payment authorization request")
public record AuthorizeRequestDto(

    @Schema(description = "Primary Account Number (16-19 digits)", example = "4111111111111111")
    @NotBlank
    @Pattern(regexp = "^[0-9]{13,19}$", message = "Invalid PAN format")
    String pan,

    @Schema(description = "Expiry month (1-12)", example = "12")
    @Min(1) @Max(12)
    short expiryMonth,

    @Schema(description = "Expiry year (4 digits)", example = "2028")
    @Min(2024) @Max(2040)
    short expiryYear,

    @Schema(description = "Cardholder name (optional)")
    @Size(max = 26)
    String cardholderName,

    @Schema(description = "Amount in kopecks (must be > 0)", example = "150000")
    @Positive
    long amountKopecks,

    @Schema(description = "ISO 4217 numeric currency code", example = "643")
    @NotBlank
    @Pattern(regexp = "^(643|840|978|156)$", message = "Unsupported currency")
    String currencyCode,

    @Schema(description = "ISO 18245 Merchant Category Code", example = "5411")
    @NotBlank
    @Pattern(regexp = "^[0-9]{4}$", message = "Invalid MCC")
    String mcc,

    @Schema(description = "Transaction type", example = "PURCHASE")
    @NotNull
    String transactionType,

    @Schema(description = "Optional key-value metadata (order_id, description, etc.)")
    Map<String, String> metadata
) {}
