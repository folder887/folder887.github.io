package ru.paycore.settlement.infrastructure.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import ru.paycore.settlement.app.usecase.SettlementUseCase;
import ru.paycore.settlement.infrastructure.kafka.dto.CapturedEvent;

/**
 * Consumes CAPTURED events from Processing Core.
 * Each captured transaction is added to the merchant's settlement batch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CapturedEventListener {

    private final SettlementUseCase settlementUseCase;

    @RetryableTopic(
        attempts = "5",
        backoff = @Backoff(delay = 2000, multiplier = 2.0, maxDelay = 30000),
        dltTopicSuffix = ".DLT"
    )
    @KafkaListener(
        topics = "payment.captured",
        groupId = "paycore-settlement",
        concurrency = "3"
    )
    public void onCaptured(ConsumerRecord<String, CapturedEvent> record) {
        CapturedEvent event = record.value();
        log.info("action=captured_event_received transaction_id={} merchant_id={} amount={}",
            event.transactionId(), event.merchantId(), event.amountKopecks());

        settlementUseCase.addToBatch(event);
    }
}
