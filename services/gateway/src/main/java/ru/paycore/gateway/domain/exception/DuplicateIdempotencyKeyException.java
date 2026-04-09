package ru.paycore.gateway.domain.exception;

public final class DuplicateIdempotencyKeyException extends PayCoreException {
    public DuplicateIdempotencyKeyException(String key) {
        super(ErrorCode.DUPLICATE_IDEMPOTENCY_KEY,
            "Idempotency key already used with different request body: " + key);
    }
}
