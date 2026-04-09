package ru.paycore.gateway.infrastructure.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import ru.paycore.gateway.domain.exception.ProcessorUnavailableException;
import ru.paycore.gateway.domain.model.PaymentRequest;
import ru.paycore.gateway.domain.model.PaymentResponse;
import ru.paycore.gateway.infrastructure.kafka.dto.AuthorizeCommand;
import ru.paycore.gateway.infrastructure.kafka.dto.ReversalCommand;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes payment commands to Kafka and synchronously waits for the reply.
 *
 * Gateway → Kafka topic "payment.commands" → Processing Core
 * Processing Core → Kafka topic "payment.replies.{correlationId}" → Gateway
 *
 * Timeout: configured in application.yml (default 2000ms).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventPublisher {

    private static final String COMMANDS_TOPIC = "payment.commands";
    private static final String REVERSALS_TOPIC = "payment.reversals";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final PaymentReplyListener replyListener;

    @Value("${paycore.gateway.timeout.processing-ms:2000}")
    private long processingTimeoutMs;

    public PaymentResponse publishAndAwait(PaymentRequest request) {
        var command = AuthorizeCommand.from(request);
        var future = replyListener.registerCorrelation(request.id().toString());

        try {
            SendResult<String, Object> sendResult = kafkaTemplate
                .send(COMMANDS_TOPIC, request.merchantId().toString(), command)
                .get(1000, TimeUnit.MILLISECONDS);

            log.debug("action=command_sent topic={} partition={} offset={} correlation_id={}",
                sendResult.getRecordMetadata().topic(),
                sendResult.getRecordMetadata().partition(),
                sendResult.getRecordMetadata().offset(),
                request.id());

        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            replyListener.removeCorrelation(request.id().toString());
            Thread.currentThread().interrupt();
            throw new ProcessorUnavailableException("Failed to send command to Kafka", e);
        }

        try {
            return future.get(processingTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            replyListener.removeCorrelation(request.id().toString());
            throw new ProcessorUnavailableException("Processing timeout after " + processingTimeoutMs + "ms", e);
        } catch (ExecutionException | InterruptedException e) {
            replyListener.removeCorrelation(request.id().toString());
            Thread.currentThread().interrupt();
            throw new ProcessorUnavailableException("Processing failed", e);
        }
    }

    public PaymentResponse publishReversalAndAwait(String transactionId, String idempotencyKey) {
        var command = ReversalCommand.of(transactionId, idempotencyKey);
        var future = replyListener.registerCorrelation(idempotencyKey);

        try {
            kafkaTemplate.send(REVERSALS_TOPIC, transactionId, command)
                .get(1000, TimeUnit.MILLISECONDS);
            return future.get(processingTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            replyListener.removeCorrelation(idempotencyKey);
            Thread.currentThread().interrupt();
            throw new ProcessorUnavailableException("Reversal processing failed", e);
        }
    }
}
