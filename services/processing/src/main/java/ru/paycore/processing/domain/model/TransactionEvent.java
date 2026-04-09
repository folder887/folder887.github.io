package ru.paycore.processing.domain.model;

/**
 * Events that trigger state transitions.
 * Each event knows its target state.
 */
public enum TransactionEvent {
    FRAUD_CHECK_STARTED(TransactionState.FRAUD_CHECKING),
    FRAUD_CHECK_PASSED(TransactionState.FRAUD_CHECKED),
    FRAUD_CHECK_FAILED(TransactionState.DECLINED_FRAUD),
    AUTHORIZATION_STARTED(TransactionState.AUTHORIZING),
    AUTHORIZATION_APPROVED(TransactionState.AUTHORIZED),
    AUTHORIZATION_DECLINED_FUNDS(TransactionState.DECLINED_INSUFFICIENT_FUNDS),
    AUTHORIZATION_DECLINED_BLOCKED(TransactionState.DECLINED_BLOCKED),
    AUTHORIZATION_DECLINED_LIMIT(TransactionState.DECLINED_LIMIT_EXCEEDED),
    AUTHORIZATION_PROCESSOR_ERROR(TransactionState.DECLINED_PROCESSOR_ERROR),
    CAPTURE_STARTED(TransactionState.CAPTURING),
    CAPTURE_COMPLETED(TransactionState.CAPTURED),
    CAPTURE_FAILED(TransactionState.DECLINED_PROCESSOR_ERROR),
    SETTLEMENT_QUEUED(TransactionState.SETTLEMENT_QUEUED),
    SETTLEMENT_COMPLETED(TransactionState.SETTLED),
    REVERSAL_STARTED(TransactionState.REVERSING),
    REVERSAL_COMPLETED(TransactionState.REVERSED),
    REVERSAL_FAILED(TransactionState.DECLINED_PROCESSOR_ERROR),
    EXPIRED(TransactionState.EXPIRED);

    private final TransactionState targetState;

    TransactionEvent(TransactionState targetState) {
        this.targetState = targetState;
    }

    public TransactionState targetState() {
        return targetState;
    }
}
