package ru.paycore.gateway.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import ru.paycore.gateway.app.port.RateLimitPort;

import java.time.Duration;
import java.util.List;

/**
 * Distributed rate limiter using Redis + Lua (atomic INCR + EXPIRE).
 *
 * Uses sliding window via 1-second TTL bucket.
 * Lua script ensures atomicity — no race condition between INCR and EXPIRE.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRateLimitAdapter implements RateLimitPort {

    private final StringRedisTemplate redisTemplate;

    // Lua: increment counter, set TTL on first call, return current count
    private static final RedisScript<Long> INCREMENT_SCRIPT = RedisScript.of(
        """
        local current = redis.call('INCR', KEYS[1])
        if current == 1 then
            redis.call('EXPIRE', KEYS[1], 1)
        end
        return current
        """,
        Long.class
    );

    @Override
    public boolean tryAcquire(String bucketKey, int maxRequests) {
        try {
            Long count = redisTemplate.execute(
                INCREMENT_SCRIPT,
                List.of("paycore:ratelimit:" + bucketKey)
            );
            if (count == null) {
                return true; // Redis unavailable — fail open (don't block payments)
            }
            boolean allowed = count <= maxRequests;
            if (!allowed) {
                log.warn("action=rate_limit_exceeded bucket={} count={} max={}", bucketKey, count, maxRequests);
            }
            return allowed;
        } catch (Exception e) {
            log.error("action=rate_limit_error bucket={} cause={}", bucketKey, e.getMessage());
            return true; // Fail open — availability > throttling in degraded state
        }
    }
}
