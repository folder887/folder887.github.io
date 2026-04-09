package ru.paycore.gateway.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import ru.paycore.gateway.app.port.IdempotencyPort;
import ru.paycore.gateway.domain.model.PaymentResponse;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed idempotency store.
 * Key: "idempotency:{key}"  TTL: 24h (configurable)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisIdempotencyAdapter implements IdempotencyPort {

    private static final String KEY_PREFIX = "paycore:idempotency:";

    private final RedisTemplate<String, PaymentResponse> redisTemplate;

    @Value("${paycore.gateway.idempotency-ttl-seconds:86400}")
    private long ttlSeconds;

    @Override
    public Optional<PaymentResponse> find(String idempotencyKey) {
        try {
            PaymentResponse cached = redisTemplate.opsForValue().get(KEY_PREFIX + idempotencyKey);
            return Optional.ofNullable(cached);
        } catch (Exception e) {
            // Redis unavailable — allow pass-through (idempotency degraded, not broken)
            log.warn("action=idempotency_redis_miss key={} cause={}", idempotencyKey, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void store(String idempotencyKey, PaymentResponse response) {
        try {
            redisTemplate.opsForValue().set(
                KEY_PREFIX + idempotencyKey,
                response,
                Duration.ofSeconds(ttlSeconds)
            );
        } catch (Exception e) {
            log.warn("action=idempotency_store_failed key={} cause={}", idempotencyKey, e.getMessage());
            // Non-fatal: next request will re-process (idempotency key was not corrupted)
        }
    }
}
