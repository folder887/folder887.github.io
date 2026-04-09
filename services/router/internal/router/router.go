package router

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"
)

// Network constants for supported payment networks.
const (
	NetworkVISA       = "VISA"
	NetworkMastercard = "MASTERCARD"
	NetworkMIR        = "MIR"

	// redisKeyPrefix is the Redis key template for BIN-to-network routing.
	// Full key example: "paycore:routing:427022"
	redisKeyPrefix = "paycore:routing:"

	// redisTimeout is the hard ceiling for a Redis round-trip.
	// Exceeding it triggers fail-open fallback to avoid adding latency to the
	// critical path (p99 target < 5 ms).
	redisTimeout = 1 * time.Millisecond
)

// processorEndpoints maps each network name to its upstream processor URL.
// In production these should come from config/env; kept here as typed
// constants so callers never deal with raw strings.
var processorEndpoints = map[string]string{
	NetworkVISA:       "http://processor-visa:8091",
	NetworkMastercard: "http://processor-mastercard:8092",
	NetworkMIR:        "http://processor-mir:8093",
}

// defaultNetwork is used when the Redis lookup misses or times out
// (fail-open strategy).
const defaultNetwork = NetworkVISA

// Prometheus metrics — registered once at package init via promauto so they
// survive across handler instances.
var (
	metricRoutingDecisionsTotal = promauto.NewCounterVec(
		prometheus.CounterOpts{
			Name: "routing_decisions_total",
			Help: "Total number of routing decisions made, labelled by resolved network.",
		},
		[]string{"network", "source"}, // source: "redis" | "fallback"
	)

	metricRoutingLatency = promauto.NewHistogram(
		prometheus.HistogramOpts{
			Name: "routing_latency_seconds",
			Help: "End-to-end latency of the routing decision (Redis lookup + logic).",
			// Buckets are sub-millisecond to sub-5ms to track the p99 SLO.
			Buckets: []float64{0.0001, 0.0005, 0.001, 0.002, 0.003, 0.005, 0.010},
		},
	)
)

// Router resolves a 6-digit BIN prefix to a payment network and its upstream
// processor endpoint.
type Router struct {
	redis  *redis.Client
	logger *zap.Logger
}

// New creates a Router backed by the given Redis client.
// redis may be nil — in that case every lookup falls back to the default
// network immediately (useful for unit tests without a Redis instance).
func New(rdb *redis.Client, logger *zap.Logger) *Router {
	return &Router{
		redis:  rdb,
		logger: logger,
	}
}

// RouteResult holds the outcome of a routing decision.
type RouteResult struct {
	Network           string
	ProcessorEndpoint string
	// Source records how the decision was made for observability.
	Source string // "redis" | "fallback"
}

// Route resolves the payment network for the given 6-digit BIN prefix.
//
// Algorithm:
//  1. Look up "paycore:routing:{binPrefix}" in Redis with a 1 ms deadline.
//  2. On hit — return the stored network.
//  3. On miss or timeout — fail-open with the default network.
//
// All paths record Prometheus metrics and are safe to call concurrently.
func (r *Router) Route(ctx context.Context, binPrefix string) (RouteResult, error) {
	start := time.Now()

	network, source, err := r.resolveNetwork(ctx, binPrefix)
	if err != nil {
		// resolveNetwork already logged the error; propagate so the handler
		// can return an appropriate HTTP status.
		return RouteResult{}, err
	}

	elapsed := time.Since(start)
	metricRoutingLatency.Observe(elapsed.Seconds())
	metricRoutingDecisionsTotal.WithLabelValues(network, source).Inc()

	endpoint, ok := processorEndpoints[network]
	if !ok {
		// Unknown network stored in Redis — treat as misconfiguration and
		// fall back rather than returning a hard error.
		r.logger.Warn("unknown network in routing table, falling back",
			zap.String("network", network),
			zap.String("bin_prefix", binPrefix),
		)
		network = defaultNetwork
		endpoint = processorEndpoints[defaultNetwork]
		source = "fallback"
	}

	r.logger.Debug("routing decision",
		zap.String("bin_prefix", binPrefix),
		zap.String("network", network),
		zap.String("source", source),
		zap.Duration("elapsed", elapsed),
	)

	return RouteResult{
		Network:           network,
		ProcessorEndpoint: endpoint,
		Source:            source,
	}, nil
}

// resolveNetwork performs the Redis lookup and encapsulates the fail-open logic.
func (r *Router) resolveNetwork(ctx context.Context, binPrefix string) (network, source string, err error) {
	if r.redis == nil {
		return defaultNetwork, "fallback", nil
	}

	// Enforce the 1 ms Redis timeout regardless of what the parent context says.
	rctx, cancel := context.WithTimeout(ctx, redisTimeout)
	defer cancel()

	key := redisKeyPrefix + binPrefix
	val, redisErr := r.redis.Get(rctx, key).Result()

	switch {
	case redisErr == nil:
		// Cache hit.
		return val, "redis", nil

	case errors.Is(redisErr, redis.Nil):
		// Key not present — this BIN is unmapped; use default.
		r.logger.Debug("bin prefix not found in routing table, using default",
			zap.String("bin_prefix", binPrefix),
			zap.String("default_network", defaultNetwork),
		)
		return defaultNetwork, "fallback", nil

	case errors.Is(redisErr, context.DeadlineExceeded) || isTimeoutError(redisErr):
		// Redis did not respond within 1 ms — fail-open to protect the SLO.
		r.logger.Warn("redis routing lookup timed out, failing open",
			zap.String("bin_prefix", binPrefix),
			zap.Error(redisErr),
		)
		return defaultNetwork, "fallback", nil

	default:
		// Unexpected Redis error — still fail-open but log at error level.
		r.logger.Error("redis routing lookup error, failing open",
			zap.String("bin_prefix", binPrefix),
			zap.Error(redisErr),
		)
		return defaultNetwork, "fallback", nil
	}
}

// isTimeoutError reports whether err looks like a network/context timeout.
func isTimeoutError(err error) bool {
	if err == nil {
		return false
	}
	type timeouter interface{ Timeout() bool }
	var t timeouter
	if errors.As(err, &t) {
		return t.Timeout()
	}
	return false
}

// SeedRoutes writes a set of BIN → network mappings into Redis.
// Intended for integration tests and local development seeding; not called in
// production hot paths.
func SeedRoutes(ctx context.Context, rdb *redis.Client, routes map[string]string) error {
	pipe := rdb.Pipeline()
	for bin, network := range routes {
		key := fmt.Sprintf("%s%s", redisKeyPrefix, bin)
		pipe.Set(ctx, key, network, 0) // no expiry
	}
	_, err := pipe.Exec(ctx)
	return err
}
