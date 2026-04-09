package ru.paycore.gateway.app.usecase;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.paycore.gateway.app.port.IdempotencyPort;
import ru.paycore.gateway.app.port.PaymentGatewayPort;
import ru.paycore.gateway.app.port.RateLimitPort;
import ru.paycore.gateway.domain.exception.LimitExceededException;
import ru.paycore.gateway.domain.exception.ProcessorUnavailableException;
import ru.paycore.gateway.domain.model.PaymentRequest;
import ru.paycore.gateway.domain.model.PaymentResponse;
import ru.paycore.gateway.infrastructure.kafka.PaymentEventPublisher;

import java.time.Instant;

/**
 * Core use case: authorize a payment through the gateway.
 *
 * Flow:
 *   1. Rate limit check (merchant + IP)
 *   2. Idempotency lookup (Redis → PG fallback)
 *   3. Route to Processing Core via Kafka
 *   4. Store idempotency result
 *   5. Return response
 *
 * All external calls have timeouts. Circuit breaker protects Processing Core.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthorizePaymentUseCase implements PaymentGatewayPort {

    private final IdempotencyPort idempotencyPort;
    private final RateLimitPort rateLimitPort;
    private final PaymentEventPublisher eventPublisher;

    private static final int MERCHANT_RPS_LIMIT = 100;

    @Override
    @CircuitBreaker(name = "processing-service", fallbackMethod = "processingFallback")
    public PaymentResponse authorize(PaymentRequest request) {
        log.info("action=authorize_start idempotency_key={} merchant_id={} amount={}",
            request.idempotencyKey(), request.merchantId(), request.amount());

        checkRateLimit(request);

        var cached = idempotencyPort.find(request.idempotencyKey());
        if (cached.isPresent()) {
            log.info("action=idempotency_hit idempotency_key={}", request.idempotencyKey());
            return cached.get();
        }

        // Publish to Kafka — Processing Core consumes and responds via reply topic
        PaymentResponse response = eventPublisher.publishAndAwait(request);

        idempotencyPort.store(request.idempotencyKey(), response);

        log.info("action=authorize_complete idempotency_key={} transaction_id={} status={}",
            request.idempotencyKey(), response.transactionId(), response.status());

        return response;
    }

    @Override
    public PaymentResponse reverse(String transactionId, String idempotencyKey) {
        log.info("action=reverse_start transaction_id={} idempotency_key={}",
            transactionId, idempotencyKey);

        var cached = idempotencyPort.find(idempotencyKey);
        if (cached.isPresent()) {
            return cached.get();
        }

        PaymentResponse response = eventPublisher.publishReversalAndAwait(transactionId, idempotencyKey);
        idempotencyPort.store(idempotencyKey, response);
        return response;
    }

    private void checkRateLimit(PaymentRequest request) {
        String merchantBucket = "merchant:%s:per-second".formatted(request.merchantId());
        if (!rateLimitPort.tryAcquire(merchantBucket, MERCHANT_RPS_LIMIT)) {
            throw new LimitExceededException(
                "Merchant %s exceeded %d RPS".formatted(request.merchantId(), MERCHANT_RPS_LIMIT));
        }
    }

    @SuppressWarnings("unused")
    private PaymentResponse processingFallback(PaymentRequest request, Throwable cause) {
        log.error("action=circuit_breaker_open merchant_id={} cause={}", request.merchantId(), cause.getMessage());
        throw new ProcessorUnavailableException("Circuit breaker open", cause);
    }
}
