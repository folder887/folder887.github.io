package ru.paycore.gateway.domain.exception;

public final class CardBlockedException extends PayCoreException {
    public CardBlockedException(String panLastFour) {
        super(ErrorCode.CARD_BLOCKED, "Card ending in " + panLastFour + " is blocked");
    }
}
