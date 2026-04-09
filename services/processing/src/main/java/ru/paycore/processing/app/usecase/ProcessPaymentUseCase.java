package ru.paycore.processing.app.usecase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.paycore.processing.domain.model.TransactionState;
import ru.paycore.processing.infrastructure.kafka.FraudCheckClient;
import ru.paycore.processing.infrastructure.kafka.PaymentReplyPublisher;
import ru.paycore.processing.infrastructure.kafka.dto.AuthorizeCommand;

/**
 * Core payment processing use case.
 *
 * Critical path (target: < 300ms p99 end-to-end):
 *   1. Fraud check (async, timeout 80ms)
 *   2. Reserve balance: FOR UPDATE NOWAIT + double-entry journal
 *   3. Publish reply to Gateway
 *
 * ALL monetary operations are in a single DB transaction (in PaymentTransactionService).
 * No 2PC between services — saga pattern via Kafka events.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessPaymentUseCase {

    private final FraudCheckClient fraudCheckClient;
    private final PaymentReplyPublisher replyPublisher;
    private final PaymentTransactionService transactionService;

    /**
     * Entry point — called by Kafka listener consuming "payment.commands".
     */
    public void process(AuthorizeCommand command) {
        log.info("action=process_start correlation_id={} merchant_id={} amount={}",
            command.correlationId(), command.merchantId(), command.amountKopecks());

        try {
            var fraudResult = fraudCheckClient.checkWithTimeout(command);

            if (fraudResult.declined()) {
                log.warn("action=fraud_declined correlation_id={} score={}",
                    command.correlationId(), fraudResult.score());
                transactionService.persistDeclined(command, TransactionState.DECLINED_FRAUD, "FRAUD_SUSPECTED");
                replyPublisher.publishDeclined(command.correlationId(), "FRAUD_SUSPECTED");
                return;
            }

            transactionService.authorizeWithDoubleEntry(command);

        } catch (InsufficientBalanceException e) {
            log.info("action=insufficient_funds correlation_id={}", command.correlationId());
            transactionService.persistDeclined(command, TransactionState.DECLINED_INSUFFICIENT_FUNDS, "INSUFFICIENT_FUNDS");
            replyPublisher.publishDeclined(command.correlationId(), "INSUFFICIENT_FUNDS");
        } catch (PaymentTransactionService.AcquirerDeclinedException e) {
            log.info("action=acquirer_declined correlation_id={} code={}", command.correlationId(), e.getDeclineCode());
            transactionService.persistDeclined(command, TransactionState.DECLINED_PROCESSOR_ERROR, e.getDeclineCode());
            replyPublisher.publishDeclined(command.correlationId(), e.getDeclineCode());
        } catch (Exception e) {
            log.error("action=processing_error correlation_id={}", command.correlationId(), e);
            transactionService.persistDeclined(command, TransactionState.DECLINED_PROCESSOR_ERROR, "PROCESSOR_ERROR");
            replyPublisher.publishDeclined(command.correlationId(), "PROCESSOR_UNAVAILABLE");
        }
    }

    public static class InsufficientBalanceException extends RuntimeException {
        public InsufficientBalanceException(String accountId) {
            super("Insufficient balance on account: " + accountId);
        }
    }
}
