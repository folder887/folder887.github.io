package ru.paycore.gateway.app.port;

import ru.paycore.gateway.domain.model.PaymentResponse;

import java.util.Optional;

/**
 * Secondary (outbound) port for idempotency key management.
 * Primary store: Redis. Persistent fallback: PostgreSQL.
 */
public interface IdempotencyPort {

    /**
     * Look up a cached response for this idempotency key.
     */
    Optional<PaymentResponse> find(String idempotencyKey);

    /**
     * Store the response for this key with configured TTL.
     */
    void store(String idempotencyKey, PaymentResponse response);
}
