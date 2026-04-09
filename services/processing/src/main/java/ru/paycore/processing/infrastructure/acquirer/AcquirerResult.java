package ru.paycore.processing.infrastructure.acquirer;

/**
 * Unified result from any acquirer.
 *
 * Whether it's YooKassa, Tinkoff, or Stripe — the processing core
 * always gets back this same structure.
 */
public record AcquirerResult(
    /** true = authorized, false = declined or error. */
    boolean success,

    /** Acquirer's transaction ID (e.g. YooKassa payment UUID). Stored for reconciliation. */
    String externalId,

    /** 6-digit auth code from the card network. Null if declined. */
    String authCode,

    /** Acquirer-specific decline code. Null if success. */
    String declineCode,

    /** Human-readable decline reason for logging. Null if success. */
    String declineMessage,

    /** Which acquirer processed this (yookassa / tinkoff / internal). */
    String acquirerName
) {
    public static AcquirerResult authorized(String externalId, String authCode, String acquirerName) {
        return new AcquirerResult(true, externalId, authCode, null, null, acquirerName);
    }

    public static AcquirerResult declined(String declineCode, String declineMessage, String acquirerName) {
        return new AcquirerResult(false, null, null, declineCode, declineMessage, acquirerName);
    }

    public static AcquirerResult error(String message, String acquirerName) {
        return new AcquirerResult(false, null, null, "PROCESSOR_ERROR", message, acquirerName);
    }

    /** Maps acquirer decline code to our internal reason string. */
    public String toInternalDeclineReason() {
        if (declineCode == null) return "PROCESSOR_UNAVAILABLE";
        return switch (declineCode) {
            case "insufficient_funds", "INSUFFICIENT_FUNDS" -> "INSUFFICIENT_FUNDS";
            case "card_expired", "EXPIRED_CARD"            -> "DECLINED_PROCESSOR_ERROR";
            case "fraud_suspected", "FRAUD"                -> "FRAUD_SUSPECTED";
            case "limit_exceeded", "LIMIT_EXCEEDED"        -> "DECLINED_LIMIT_EXCEEDED";
            default -> "PROCESSOR_UNAVAILABLE";
        };
    }
}
