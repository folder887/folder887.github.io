package ru.paycore.processing.app.usecase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.paycore.processing.domain.model.TransactionState;
import ru.paycore.processing.infrastructure.acquirer.AcquirerRoutingService;
import ru.paycore.processing.infrastructure.persistence.entity.TransactionEntity;
import ru.paycore.processing.infrastructure.persistence.repository.JournalEntryRepository;
import ru.paycore.processing.infrastructure.persistence.repository.TransactionRepository;
import ru.paycore.processing.infrastructure.kafka.PaymentReplyPublisher;
import ru.paycore.processing.infrastructure.kafka.dto.AuthorizeCommand;

import java.util.UUID;

/**
 * Transactional monetary operations — extracted from ProcessPaymentUseCase
 * to avoid Spring AOP self-invocation proxy bypass.
 *
 * Flow:
 *   1. Call AcquirerRoutingService → selects best acquirer (YooKassa/Tinkoff/internal)
 *   2. Acquirer returns authCode + externalId (their transaction ID)
 *   3. Save transaction to OUR DB (for analytics + reconciliation)
 *   4. Publish payment.replies → Gateway
 *
 * In INTERNAL mode: uses our own ledger (no external calls, no license needed).
 * In ACQUIRER mode: forwards to real acquirer, records result in our DB.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTransactionService {

    private final TransactionRepository transactionRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final PaymentReplyPublisher replyPublisher;
    private final AcquirerRoutingService acquirerRoutingService;

    /**
     * Authorize payment via best available acquirer, then record result.
     *
     * @throws ProcessPaymentUseCase.InsufficientBalanceException if acquirer declines for funds
     */
    @Transactional
    public void authorizeWithDoubleEntry(AuthorizeCommand command) {
        // Step 1: Route to acquirer and attempt authorization
        var acquirerRequest = AcquirerRoutingService.fromCommand(command);
        var result = acquirerRoutingService.authorize(acquirerRequest);

        if (!result.success()) {
            if ("INSUFFICIENT_FUNDS".equals(result.declineCode())) {
                throw new ProcessPaymentUseCase.InsufficientBalanceException(
                    command.merchantId().toString()
                );
            }
            throw new AcquirerDeclinedException(result.declineCode(), result.declineMessage());
        }

        // Step 2: Record authorized transaction in our DB
        var rrn = generateRrn();

        var transaction = TransactionEntity.builder()
            .id(command.correlationId())
            .idempotencyKey(command.idempotencyKey())
            .merchantId(command.merchantId())
            .amount(command.amountKopecks())
            .currency(command.currencyCode())
            .status(TransactionState.AUTHORIZED)
            .mcc(command.mcc())
            .authCode(result.authCode())
            .rrn(rrn)
            .acquirerName(result.acquirerName())
            .acquirerTxId(result.externalId())
            .build();

        transactionRepository.save(transaction);

        // Step 3: Double-entry journal (for reconciliation — even in ACQUIRER mode)
        // We record what the acquirer processed, not actual balance movement
        try {
            journalEntryRepository.insertPair(
                UUID.randomUUID(),
                UUID.randomUUID(),
                command.correlationId(),
                command.merchantId(),
                UUID.fromString("018f1234-0000-7000-8000-000000000010"),
                command.amountKopecks(),
                "AUTHORIZATION",
                "Payment via %s: %s".formatted(result.acquirerName(), result.externalId()),
                0L  // balance snapshot not applicable in ACQUIRER mode
            );
        } catch (Exception e) {
            // Journal write is best-effort — don't fail the authorization
            log.warn("action=journal_write_failed correlation_id={} error={}",
                command.correlationId(), e.getMessage());
        }

        log.info("action=authorized correlation_id={} acquirer={} auth_code={} rrn={}",
            command.correlationId(), result.acquirerName(), result.authCode(), rrn);

        replyPublisher.publishAuthorized(command.correlationId(), transaction, result.authCode(), rrn);
    }

    @Transactional
    public void persistDeclined(AuthorizeCommand command, TransactionState state, String reason) {
        var transaction = TransactionEntity.builder()
            .id(command.correlationId())
            .idempotencyKey(command.idempotencyKey())
            .merchantId(command.merchantId())
            .amount(command.amountKopecks())
            .currency(command.currencyCode())
            .status(state)
            .mcc(command.mcc())
            .declineReason(reason)
            .build();
        transactionRepository.save(transaction);
    }

    private String generateRrn() {
        return "%012d".formatted(System.currentTimeMillis() % 1_000_000_000_000L);
    }

    /** Thrown when acquirer declines for a business reason (not infrastructure error). */
    public static class AcquirerDeclinedException extends RuntimeException {
        private final String declineCode;

        public AcquirerDeclinedException(String declineCode, String message) {
            super("Acquirer declined: %s — %s".formatted(declineCode, message));
            this.declineCode = declineCode;
        }

        public String getDeclineCode() { return declineCode; }
    }
}
