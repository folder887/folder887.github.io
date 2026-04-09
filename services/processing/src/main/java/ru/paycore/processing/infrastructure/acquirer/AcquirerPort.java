package ru.paycore.processing.infrastructure.acquirer;

/**
 * Unified interface for all payment acquirers.
 *
 * Every acquirer (YooKassa, Tinkoff, Stripe, etc.) implements this port.
 * The Processing service never knows which acquirer it's talking to —
 * AcquirerRoutingService decides and returns the right adapter.
 *
 * Contracts:
 *   - authorize() must be idempotent (same externalRef → same result)
 *   - timeout handling is the adapter's responsibility
 *   - never throws — always returns AcquirerResult (success or failure)
 */
public interface AcquirerPort {

    /**
     * Attempt to authorize a payment with this acquirer.
     *
     * @param request unified payment request
     * @return result with success flag, external IDs, auth code, or decline reason
     */
    AcquirerResult authorize(AcquirerRequest request);

    /**
     * Unique name of this acquirer (used for logging, metrics, DB records).
     */
    String name();

    /**
     * Check if this acquirer can handle the given request.
     * Routing service calls this before selecting an acquirer.
     */
    default boolean supports(AcquirerRequest request) {
        return true;
    }
}
