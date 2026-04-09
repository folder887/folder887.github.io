package ru.paycore.settlement.infrastructure.persistence.entity;

public enum SettlementBatchStatus {
    PENDING,     // accumulating transactions
    PROCESSING,  // settlement in progress
    SETTLED,     // successfully settled, funds transferred
    FAILED       // settlement failed — requires manual review
}
