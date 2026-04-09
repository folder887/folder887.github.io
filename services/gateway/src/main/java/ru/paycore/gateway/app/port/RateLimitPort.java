package ru.paycore.gateway.app.port;

/**
 * Secondary port for distributed rate limiting (backed by Redis).
 */
public interface RateLimitPort {

    /**
     * Check and increment rate limit counter.
     *
     * @param bucketKey  e.g. "merchant:{id}:per-second" or "ip:{addr}:per-minute"
     * @param maxRequests maximum allowed requests in the window
     * @return true if the request is within the limit, false if throttled
     */
    boolean tryAcquire(String bucketKey, int maxRequests);
}
