package ru.paycore.processing.infrastructure.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import ru.paycore.processing.infrastructure.kafka.dto.PaymentReplyEvent;
import ru.paycore.processing.infrastructure.persistence.entity.TransactionEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes payment processing results back to Gateway via Kafka.
 *
 * Topic: "payment.replies" — consumed by Gateway's PaymentReplyListener.
 * Key: correlationId (string) — enables partition routing by transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReplyPublisher {

    private static final String TOPIC = "payment.replies";

    private final KafkaTemplate<String, PaymentReplyEvent> kafkaTemplate;

    public void publishAuthorized(
        UUID correlationId,
        TransactionEntity transaction,
        String authCode,
        String rrn
    ) {
        var event = new PaymentReplyEvent(
            correlationId.toString(),
            transaction.getIdempotencyKey(),
            "AUTHORIZED",
            authCode,
            rrn,
            null,
            transaction.getAmount(),
            transaction.getCurrency(),
            Instant.now()
        );
        send(correlationId.toString(), event);

        log.info("action=reply_published status=AUTHORIZED correlation_id={} auth_code={}",
            correlationId, authCode);
    }

    public void publishDeclined(UUID correlationId, String declineCode) {
        var event = new PaymentReplyEvent(
            correlationId.toString(),
            "",
            "DECLINED",
            null,
            null,
            declineCode,
            0L,
            "643",
            Instant.now()
        );
        send(correlationId.toString(), event);

        log.info("action=reply_published status=DECLINED correlation_id={} reason={}",
            correlationId, declineCode);
    }

    private void send(String key, PaymentReplyEvent event) {
        kafkaTemplate.send(TOPIC, key, event)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("action=reply_send_failed correlation_id={}", key, ex);
                }
            });
    }
}
