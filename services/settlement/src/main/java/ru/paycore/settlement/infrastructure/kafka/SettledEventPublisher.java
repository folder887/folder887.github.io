package ru.paycore.settlement.infrastructure.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import ru.paycore.settlement.infrastructure.kafka.dto.SettledEvent;
import ru.paycore.settlement.infrastructure.persistence.entity.SettlementBatchEntity;

import java.time.Instant;

/**
 * Publishes SETTLED events to Kafka after a batch is successfully settled.
 * Topic: "payment.settled" — consumed by Core Banking and analytics pipeline.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettledEventPublisher {

    private static final String TOPIC = "payment.settled";

    private final KafkaTemplate<String, SettledEvent> kafkaTemplate;

    public void publishBatchSettled(SettlementBatchEntity batch) {
        var event = new SettledEvent(
            batch.getId().toString(),
            batch.getMerchantId().toString(),
            batch.getTransactionCount(),
            batch.getGrossAmount(),
            batch.getFeeAmount(),
            batch.getNetAmount(),
            batch.getPeriodFrom(),
            batch.getPeriodTo(),
            Instant.now()
        );

        kafkaTemplate.send(TOPIC, batch.getMerchantId().toString(), event)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("action=settled_event_send_failed batch_id={}", batch.getId(), ex);
                } else {
                    log.info("action=settled_event_published batch_id={} merchant_id={} net_kopecks={}",
                        batch.getId(), batch.getMerchantId(), batch.getNetAmount());
                }
            });
    }
}
