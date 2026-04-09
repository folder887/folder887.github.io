package main

import (
	"context"
	"errors"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/redis/go-redis/v9"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/propagation"
	sdkresource "go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.24.0"
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"

	internalrouter "github.com/paycore/router/internal/router"
)

const (
	serviceName    = "paycore-router"
	serviceVersion = "1.0.0"
	listenAddr     = ":8083"
	shutdownTimeout = 10 * time.Second
)

func main() {
	// -------------------------------------------------------------------------
	// Structured logger (JSON, production-grade)
	// -------------------------------------------------------------------------
	logger := buildLogger()
	defer func() { _ = logger.Sync() }()

	logger.Info("starting paycore-router",
		zap.String("addr", listenAddr),
		zap.String("version", serviceVersion),
	)

	// -------------------------------------------------------------------------
	// OpenTelemetry — tracer provider
	// -------------------------------------------------------------------------
	tp, otelShutdown, err := initTracer(logger)
	if err != nil {
		// OTel is best-effort; log and continue.
		logger.Warn("opentelemetry init failed, tracing disabled", zap.Error(err))
	} else {
		otel.SetTracerProvider(tp)
		otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
			propagation.TraceContext{},
			propagation.Baggage{},
		))
		defer func() {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			if err := otelShutdown(ctx); err != nil {
				logger.Warn("opentelemetry shutdown error", zap.Error(err))
			}
		}()
	}

	// -------------------------------------------------------------------------
	// Redis client
	// -------------------------------------------------------------------------
	rdb := buildRedisClient(logger)
	defer func() {
		if err := rdb.Close(); err != nil {
			logger.Warn("redis close error", zap.Error(err))
		}
	}()

	// Verify Redis connectivity at startup (non-fatal — fail-open design).
	pingCtx, pingCancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer pingCancel()
	if err := rdb.Ping(pingCtx).Err(); err != nil {
		logger.Warn("redis ping failed at startup — routing will use fallback network",
			zap.Error(err),
		)
	} else {
		logger.Info("redis connected", zap.String("addr", redisAddr()))
	}

	// -------------------------------------------------------------------------
	// Router + HTTP handler
	// -------------------------------------------------------------------------
	r := internalrouter.New(rdb, logger)
	h := internalrouter.NewHandler(r, logger)

	// -------------------------------------------------------------------------
	// HTTP mux (chi)
	// -------------------------------------------------------------------------
	mux := chi.NewRouter()

	// Middleware stack — order matters.
	mux.Use(middleware.RealIP)
	mux.Use(middleware.RequestID)
	mux.Use(zapRequestLogger(logger))   // structured request logging
	mux.Use(middleware.Recoverer)       // panic → 500 without crashing
	mux.Use(middleware.Timeout(5 * time.Second)) // hard per-request timeout

	// Application routes.
	mux.Post("/api/v1/route", h.HandleRoute)
	mux.Get("/health", h.HandleHealth)

	// Prometheus scrape endpoint — separate from application routes so it can
	// be firewalled off from external traffic if needed.
	mux.Handle("/metrics", promhttp.Handler())

	// -------------------------------------------------------------------------
	// HTTP server
	// -------------------------------------------------------------------------
	srv := &http.Server{
		Addr:    listenAddr,
		Handler: mux,
		// Tight timeouts protect against slow-client attacks.
		ReadHeaderTimeout: 2 * time.Second,
		ReadTimeout:       5 * time.Second,
		WriteTimeout:      10 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	// -------------------------------------------------------------------------
	// Graceful shutdown
	// -------------------------------------------------------------------------
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	// Run server in a goroutine so we can block on the signal channel below.
	serverErr := make(chan error, 1)
	go func() {
		logger.Info("http server listening", zap.String("addr", listenAddr))
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			serverErr <- err
		}
	}()

	select {
	case sig := <-quit:
		logger.Info("shutdown signal received", zap.String("signal", sig.String()))
	case err := <-serverErr:
		logger.Error("http server error", zap.Error(err))
	}

	// Give in-flight requests up to shutdownTimeout to finish.
	shutCtx, shutCancel := context.WithTimeout(context.Background(), shutdownTimeout)
	defer shutCancel()

	if err := srv.Shutdown(shutCtx); err != nil {
		logger.Error("http server forced shutdown", zap.Error(err))
	} else {
		logger.Info("http server shutdown complete")
	}
}

// ---- Helpers ----------------------------------------------------------------

// buildLogger constructs a production zap logger that emits JSON to stdout.
// The log level can be overridden via the LOG_LEVEL environment variable
// (debug | info | warn | error).
func buildLogger() *zap.Logger {
	level := zapcore.InfoLevel
	if lvlStr := os.Getenv("LOG_LEVEL"); lvlStr != "" {
		if err := level.UnmarshalText([]byte(lvlStr)); err != nil {
			// Default to info on bad input.
			level = zapcore.InfoLevel
		}
	}

	cfg := zap.NewProductionConfig()
	cfg.Level = zap.NewAtomicLevelAt(level)
	cfg.EncoderConfig.TimeKey = "ts"
	cfg.EncoderConfig.EncodeTime = zapcore.RFC3339NanoTimeEncoder

	logger, err := cfg.Build(
		zap.Fields(
			zap.String("service", serviceName),
			zap.String("version", serviceVersion),
		),
	)
	if err != nil {
		// Fallback to a no-op logger — should never happen.
		return zap.NewNop()
	}
	return logger
}

// redisAddr returns the Redis address from REDIS_ADDR env or a sensible default.
func redisAddr() string {
	if addr := os.Getenv("REDIS_ADDR"); addr != "" {
		return addr
	}
	return "redis:6379"
}

// buildRedisClient constructs a Redis client.
// Connection parameters come from environment variables so the same binary
// runs in dev, staging and production without recompilation.
//
// Env vars:
//
//	REDIS_ADDR     — host:port (default: redis:6379)
//	REDIS_PASSWORD — optional password
//	REDIS_DB       — optional DB index (default: 0)
func buildRedisClient(logger *zap.Logger) *redis.Client {
	opts := &redis.Options{
		Addr:     redisAddr(),
		Password: os.Getenv("REDIS_PASSWORD"),
		// PoolSize is set conservatively; tune based on load-test results.
		PoolSize:        20,
		MinIdleConns:    5,
		ConnMaxIdleTime: 5 * time.Minute,
		// Dial/read/write timeouts are deliberately generous here — the 1 ms
		// routing-lookup timeout is enforced per-call in router.go via a
		// context.WithTimeout, not at the client level.
		DialTimeout:  2 * time.Second,
		ReadTimeout:  500 * time.Millisecond,
		WriteTimeout: 500 * time.Millisecond,
	}

	logger.Info("redis client configured",
		zap.String("addr", opts.Addr),
		zap.Int("pool_size", opts.PoolSize),
	)

	return redis.NewClient(opts)
}

// initTracer creates an OTLP/gRPC trace exporter and returns a TracerProvider.
// The OTLP endpoint is read from OTEL_EXPORTER_OTLP_ENDPOINT (default:
// otel-collector:4317).
func initTracer(logger *zap.Logger) (*sdktrace.TracerProvider, func(context.Context) error, error) {
	endpoint := os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
	if endpoint == "" {
		endpoint = "otel-collector:4317"
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	conn, err := grpc.DialContext(ctx, endpoint, //nolint:staticcheck // DialContext used intentionally
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithBlock(),
	)
	if err != nil {
		return nil, nil, err
	}

	exporter, err := otlptracegrpc.New(ctx, otlptracegrpc.WithGRPCConn(conn))
	if err != nil {
		return nil, nil, err
	}

	res, err := sdkresource.New(ctx,
		sdkresource.WithAttributes(
			semconv.ServiceNameKey.String(serviceName),
			semconv.ServiceVersionKey.String(serviceVersion),
		),
	)
	if err != nil {
		logger.Warn("otel resource detection partial failure", zap.Error(err))
	}

	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(res),
		// Sample 100 % in dev; in production set via OTEL_TRACES_SAMPLER env.
		sdktrace.WithSampler(sdktrace.AlwaysSample()),
	)

	shutdown := func(ctx context.Context) error {
		if err := tp.Shutdown(ctx); err != nil {
			return err
		}
		return conn.Close()
	}

	logger.Info("opentelemetry tracer initialised", zap.String("endpoint", endpoint))
	return tp, shutdown, nil
}

// zapRequestLogger returns a chi middleware that emits one structured log line
// per request.  It intentionally omits any field that could contain PAN data.
func zapRequestLogger(logger *zap.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			ww := middleware.NewWrapResponseWriter(w, r.ProtoMajor)
			start := time.Now()

			defer func() {
				logger.Info("http request",
					zap.String("method", r.Method),
					zap.String("path", r.URL.Path),
					zap.String("remote_addr", r.RemoteAddr),
					zap.String("request_id", middleware.GetReqID(r.Context())),
					zap.Int("status", ww.Status()),
					zap.Int("bytes", ww.BytesWritten()),
					zap.Duration("duration", time.Since(start)),
				)
			}()

			next.ServeHTTP(ww, r)
		})
	}
}
