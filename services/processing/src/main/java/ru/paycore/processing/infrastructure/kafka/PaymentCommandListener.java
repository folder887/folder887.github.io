package ru.paycore.processing.infrastructure.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.paycore.processing.app.usecase.ProcessPaymentUseCase;
import ru.paycore.processing.infrastructure.kafka.dto.AuthorizeCommand;

/**
 * Kafka listener for payment authorization commands.
 *
 * Retry policy:
 *   - 3 retries with exponential backoff (1s, 2s, 4s)
 *   - After 3 failures → Dead Letter Topic (payment.commands.DLT)
 *   - DLT is consumed by alerting + manual review pipeline
 *
 * Idempotency: each command has idempotencyKey checked before processing.
 * Concurrency: virtual threads (Spring Boot 3.x, Java 21).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCommandListener {

    private final ProcessPaymentUseCase processPaymentUseCase;

    @KafkaListener(
        topics = "payment.commands",
        groupId = "paycore-processing",
        concurrency = "6",  // 6 partitions / 1 thread per partition
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void onCommand(ConsumerRecord<String, AuthorizeCommand> record) {
        AuthorizeCommand command = record.value();

        log.info("action=command_received partition={} offset={} correlation_id={} merchant_id={}",
            record.partition(), record.offset(),
            command.correlationId(), command.merchantId());

        processPaymentUseCase.process(command);
    }

    @KafkaListener(
        topics = "payment.commands.DLT",
        groupId = "paycore-processing-dlt"
    )
    public void onDeadLetter(ConsumerRecord<String, AuthorizeCommand> record) {
        log.error("action=dead_letter_received partition={} offset={} correlation_id={}",
            record.partition(), record.offset(),
            record.value() != null ? record.value().correlationId() : "unknown");
        // Alert on-call engineer — integrated with PagerDuty/VictorOps in production
    }
}
