package ru.paycore.gateway.infrastructure.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.paycore.gateway.domain.model.*;
import ru.paycore.gateway.infrastructure.kafka.dto.PaymentReplyEvent;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens for authorization replies from Processing Core.
 * Correlates replies to waiting futures by correlation ID.
 */
@Slf4j
@Component
public class PaymentReplyListener {

    // correlationId → waiting future
    private final ConcurrentHashMap<String, CompletableFuture<PaymentResponse>> pending =
        new ConcurrentHashMap<>();

    public CompletableFuture<PaymentResponse> registerCorrelation(String correlationId) {
        var future = new CompletableFuture<PaymentResponse>();
        pending.put(correlationId, future);
        return future;
    }

    public void removeCorrelation(String correlationId) {
        pending.remove(correlationId);
    }

    @KafkaListener(topics = "payment.replies", groupId = "paycore-gateway-replies",
                   containerFactory = "replyKafkaListenerContainerFactory")
    public void onReply(PaymentReplyEvent event) {
        String correlationId = event.correlationId();
        CompletableFuture<PaymentResponse> future = pending.remove(correlationId);

        if (future == null) {
            log.warn("action=orphan_reply correlation_id={} — no waiting future", correlationId);
            return;
        }

        try {
            TransactionStatus status = TransactionStatus.valueOf(event.status());
            CurrencyCode currency = CurrencyCode.fromIsoCode(
                event.currencyCode() != null && !event.currencyCode().isEmpty() ? event.currencyCode() : "643"
            );
            Money amount = Money.of(event.amountKopecks(), currency);

            PaymentResponse response = new PaymentResponse(
                UUID.fromString(correlationId),
                event.idempotencyKey(),
                status,
                amount,
                event.authCode(),
                event.rrn(),
                event.declineCode(),
                event.processedAt()
            );
            future.complete(response);
        } catch (Exception e) {
            log.error("action=reply_parse_error correlation_id={}", correlationId, e);
            future.completeExceptionally(e);
        }
    }
}
