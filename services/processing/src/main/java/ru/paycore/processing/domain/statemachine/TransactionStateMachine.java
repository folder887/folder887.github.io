package ru.paycore.processing.domain.statemachine;

import ru.paycore.processing.domain.model.TransactionState;
import ru.paycore.processing.domain.model.TransactionEvent;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Explicit state machine for payment transaction lifecycle.
 *
 * State transitions:
 *   PENDING           → FRAUD_CHECKING
 *   FRAUD_CHECKING    → FRAUD_CHECKED | DECLINED_FRAUD
 *   FRAUD_CHECKED     → AUTHORIZING
 *   AUTHORIZING       → AUTHORIZED | DECLINED_INSUFFICIENT_FUNDS | DECLINED_BLOCKED
 *   AUTHORIZED        → CAPTURING | REVERSING | EXPIRED
 *   CAPTURING         → CAPTURED | DECLINED_PROCESSOR_ERROR
 *   CAPTURED          → SETTLEMENT_QUEUED
 *   SETTLEMENT_QUEUED → SETTLED
 *   REVERSING         → REVERSED | DECLINED_PROCESSOR_ERROR
 *
 * Invalid transitions throw IllegalStateException — caught at service layer.
 */
public final class TransactionStateMachine {

    // Map: currentState → set of valid target states
    private static final Map<TransactionState, Set<TransactionState>> TRANSITIONS =
        new EnumMap<>(TransactionState.class);

    static {
        TRANSITIONS.put(TransactionState.PENDING, Set.of(TransactionState.FRAUD_CHECKING));
        TRANSITIONS.put(TransactionState.FRAUD_CHECKING, Set.of(
            TransactionState.FRAUD_CHECKED,
            TransactionState.DECLINED_FRAUD
        ));
        TRANSITIONS.put(TransactionState.FRAUD_CHECKED, Set.of(TransactionState.AUTHORIZING));
        TRANSITIONS.put(TransactionState.AUTHORIZING, Set.of(
            TransactionState.AUTHORIZED,
            TransactionState.DECLINED_INSUFFICIENT_FUNDS,
            TransactionState.DECLINED_BLOCKED,
            TransactionState.DECLINED_LIMIT_EXCEEDED,
            TransactionState.DECLINED_PROCESSOR_ERROR
        ));
        TRANSITIONS.put(TransactionState.AUTHORIZED, Set.of(
            TransactionState.CAPTURING,
            TransactionState.REVERSING,
            TransactionState.EXPIRED
        ));
        TRANSITIONS.put(TransactionState.CAPTURING, Set.of(
            TransactionState.CAPTURED,
            TransactionState.DECLINED_PROCESSOR_ERROR
        ));
        TRANSITIONS.put(TransactionState.CAPTURED, Set.of(TransactionState.SETTLEMENT_QUEUED));
        TRANSITIONS.put(TransactionState.SETTLEMENT_QUEUED, Set.of(TransactionState.SETTLED));
        TRANSITIONS.put(TransactionState.REVERSING, Set.of(
            TransactionState.REVERSED,
            TransactionState.DECLINED_PROCESSOR_ERROR
        ));
        // Terminal states — no outbound transitions
        TRANSITIONS.put(TransactionState.SETTLED, Set.of());
        TRANSITIONS.put(TransactionState.REVERSED, Set.of());
        TRANSITIONS.put(TransactionState.DECLINED_FRAUD, Set.of());
        TRANSITIONS.put(TransactionState.DECLINED_INSUFFICIENT_FUNDS, Set.of());
        TRANSITIONS.put(TransactionState.DECLINED_BLOCKED, Set.of());
        TRANSITIONS.put(TransactionState.DECLINED_PROCESSOR_ERROR, Set.of());
        TRANSITIONS.put(TransactionState.DECLINED_LIMIT_EXCEEDED, Set.of());
        TRANSITIONS.put(TransactionState.EXPIRED, Set.of());
    }

    private TransactionStateMachine() {}

    /**
     * Validates and returns the next state.
     *
     * @throws IllegalStateException if transition is not allowed
     */
    public static TransactionState transition(TransactionState current, TransactionEvent event) {
        TransactionState target = event.targetState();
        Set<TransactionState> allowed = TRANSITIONS.getOrDefault(current, Set.of());

        if (!allowed.contains(target)) {
            throw new IllegalStateException(
                "Invalid transition: %s → %s (event: %s)".formatted(current, target, event)
            );
        }
        return target;
    }

    public static boolean isTerminal(TransactionState state) {
        return TRANSITIONS.getOrDefault(state, Set.of()).isEmpty();
    }
}
