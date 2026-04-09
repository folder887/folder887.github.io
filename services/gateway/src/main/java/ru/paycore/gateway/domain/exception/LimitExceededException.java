package ru.paycore.gateway.domain.exception;

public final class LimitExceededException extends PayCoreException {
    public LimitExceededException(String detail) {
        super(ErrorCode.LIMIT_EXCEEDED, "Limit exceeded: " + detail);
    }
}
