package ru.paycore.processing.infrastructure.kafka.dto;

/**
 * Result of a fraud check call.
 *
 * @param transactionId correlation id
 * @param declined      true if the transaction must be declined (DECLINE decision)
 * @param score         composite fraud score (0–2000)
 * @param decision      raw decision string: APPROVE | REVIEW | DECLINE
 */
public record FraudCheckResult(
    String transactionId,
    boolean declined,
    int score,
    String decision
) {
    public static FraudCheckResult approve(String transactionId) {
        return new FraudCheckResult(transactionId, false, 0, "APPROVE");
    }

    public static FraudCheckResult decline(String transactionId, int score) {
        return new FraudCheckResult(transactionId, true, score, "DECLINE");
    }
}
