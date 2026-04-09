package ru.paycore.gateway.domain.exception;

public final class InsufficientFundsException extends PayCoreException {
    public InsufficientFundsException(String accountId) {
        super(ErrorCode.INSUFFICIENT_FUNDS,
            "Insufficient funds on account: " + accountId);
    }
}
