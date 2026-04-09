package ru.paycore.gateway.domain.exception;

public final class InvalidRequestException extends PayCoreException {
    public InvalidRequestException(String detail) {
        super(ErrorCode.INVALID_REQUEST, detail);
    }
}
