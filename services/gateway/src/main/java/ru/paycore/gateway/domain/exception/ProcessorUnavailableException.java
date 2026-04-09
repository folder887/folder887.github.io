package ru.paycore.gateway.domain.exception;

public final class ProcessorUnavailableException extends PayCoreException {
    public ProcessorUnavailableException(String detail, Throwable cause) {
        super(ErrorCode.PROCESSOR_UNAVAILABLE, "Processor unavailable: " + detail, cause);
    }
}
