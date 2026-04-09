package ru.paycore.gateway.app.port;

import ru.paycore.gateway.domain.model.PaymentRequest;
import ru.paycore.gateway.domain.model.PaymentResponse;

/**
 * Primary (inbound) port — the only way to initiate a payment through the gateway.
 */
public interface PaymentGatewayPort {

    /**
     * Authorize a payment.
     * Idempotent: repeated calls with the same idempotency key return the cached response.
     *
     * @param request validated payment request
     * @return authorization result
     */
    PaymentResponse authorize(PaymentRequest request);

    /**
     * Reverse a previously authorized transaction.
     *
     * @param transactionId original transaction UUID
     * @param idempotencyKey caller-supplied idempotency key
     * @return reversal result
     */
    PaymentResponse reverse(String transactionId, String idempotencyKey);
}
