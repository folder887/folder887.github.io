package ru.paycore.gateway.domain.exception;

public final class FraudSuspectedException extends PayCoreException {
    public FraudSuspectedException(String transactionId, int score) {
        super(ErrorCode.FRAUD_SUSPECTED,
            "Transaction %s declined by fraud engine (score=%d)".formatted(transactionId, score));
    }
}
