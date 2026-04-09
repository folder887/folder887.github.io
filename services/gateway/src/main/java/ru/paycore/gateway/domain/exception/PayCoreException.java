package ru.paycore.gateway.domain.exception;

/**
 * Base typed exception hierarchy.
 * All domain errors extend this — never throw raw RuntimeException.
 */
public abstract sealed class PayCoreException extends RuntimeException
    permits InsufficientFundsException,
            CardBlockedException,
            FraudSuspectedException,
            ProcessorUnavailableException,
            DuplicateIdempotencyKeyException,
            InvalidRequestException,
            MerchantNotFoundException,
            LimitExceededException {

    private final ErrorCode errorCode;

    protected PayCoreException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected PayCoreException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public enum ErrorCode {
        INSUFFICIENT_FUNDS,
        CARD_BLOCKED,
        FRAUD_SUSPECTED,
        PROCESSOR_UNAVAILABLE,
        DUPLICATE_IDEMPOTENCY_KEY,
        INVALID_REQUEST,
        MERCHANT_NOT_FOUND,
        LIMIT_EXCEEDED
    }
}
