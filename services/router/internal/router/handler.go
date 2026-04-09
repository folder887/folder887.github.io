package router

import (
	"encoding/json"
	"net/http"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.uber.org/zap"
)

// tracer is the package-level OpenTelemetry tracer.
var tracer = otel.Tracer("github.com/paycore/router/internal/router")

// ---- Request / Response types -----------------------------------------------

// RouteRequest is the JSON body accepted by POST /api/v1/route.
//
// Security note: panFirstSix MUST contain only the first six digits of the
// PAN (the BIN), never a full card number.  The handler validates the length
// and logs only this field — full PAN is never accepted, stored or logged.
type RouteRequest struct {
	// CorrelationID is a client-supplied UUID used to correlate logs across
	// services.  Passed through unchanged in the response.
	CorrelationID string `json:"correlationId"`

	// PanFirstSix is the 6-digit Bank Identification Number (BIN).
	// Example: "427022"
	PanFirstSix string `json:"panFirstSix"`

	// MerchantID identifies the merchant initiating the transaction.
	MerchantID string `json:"merchantId"`

	// Amount is the transaction value in kopecks (minor currency units).
	// Using int64 avoids all floating-point rounding issues — 1 RUB = 100 kopecks.
	Amount int64 `json:"amount"`
}

// RouteResponse is the JSON body returned by POST /api/v1/route on success.
type RouteResponse struct {
	CorrelationID     string `json:"correlationId"`
	Network           string `json:"network"`
	ProcessorEndpoint string `json:"processorEndpoint"`
	// RoutedAt is an RFC 3339 timestamp with nanosecond precision recorded
	// immediately after the routing decision is made.
	RoutedAt string `json:"routedAt"`
}

// ErrorResponse is the JSON body returned on any error.
type ErrorResponse struct {
	Error string `json:"error"`
}

// HealthResponse is the JSON body returned by GET /health.
type HealthResponse struct {
	Status  string `json:"status"`
	Service string `json:"service"`
}

// ---- Handler ----------------------------------------------------------------

// Handler holds the HTTP handlers for the router service.
type Handler struct {
	router *Router
	logger *zap.Logger
}

// NewHandler creates a Handler wired to the given Router.
func NewHandler(r *Router, logger *zap.Logger) *Handler {
	return &Handler{router: r, logger: logger}
}

// HandleRoute processes POST /api/v1/route.
//
// It:
//   - Decodes and validates the request body.
//   - Delegates the routing decision to Router.Route.
//   - Returns a RouteResponse with the resolved network and processor endpoint.
//   - Logs every request (correlation ID, merchant, amount) — never PAN digits
//     beyond the first six, which are already non-sensitive (public BIN data).
func (h *Handler) HandleRoute(w http.ResponseWriter, r *http.Request) {
	// Start an OTel span so this handler participates in distributed traces.
	ctx, span := tracer.Start(r.Context(), "HandleRoute")
	defer span.End()

	start := time.Now()

	// --- Decode request ------------------------------------------------------
	var req RouteRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		h.logger.Warn("failed to decode route request",
			zap.String("remote_addr", r.RemoteAddr),
			zap.Error(err),
		)
		span.SetStatus(codes.Error, "invalid request body")
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	// --- Validate ------------------------------------------------------------
	if req.CorrelationID == "" {
		writeError(w, http.StatusBadRequest, "correlationId is required")
		return
	}
	if len(req.PanFirstSix) != 6 {
		// Reject anything that is not exactly 6 digits so we never
		// accidentally log a longer PAN fragment.
		h.logger.Warn("invalid panFirstSix length",
			zap.String("correlation_id", req.CorrelationID),
			zap.Int("length", len(req.PanFirstSix)),
		)
		span.SetStatus(codes.Error, "invalid panFirstSix")
		writeError(w, http.StatusBadRequest, "panFirstSix must be exactly 6 digits")
		return
	}
	if req.MerchantID == "" {
		writeError(w, http.StatusBadRequest, "merchantId is required")
		return
	}
	if req.Amount <= 0 {
		writeError(w, http.StatusBadRequest, "amount must be a positive integer (kopecks)")
		return
	}

	// Attach non-sensitive attributes to the span.
	span.SetAttributes(
		attribute.String("correlation_id", req.CorrelationID),
		attribute.String("merchant_id", req.MerchantID),
		attribute.String("bin_prefix", req.PanFirstSix),
		attribute.Int64("amount_kopecks", req.Amount),
	)

	// --- Route ---------------------------------------------------------------
	result, err := h.router.Route(ctx, req.PanFirstSix)
	if err != nil {
		h.logger.Error("routing decision failed",
			zap.String("correlation_id", req.CorrelationID),
			zap.String("bin_prefix", req.PanFirstSix),
			zap.Error(err),
		)
		span.SetStatus(codes.Error, "routing decision failed")
		writeError(w, http.StatusInternalServerError, "routing decision failed")
		return
	}

	routedAt := time.Now()

	// --- Log (PAN-safe) ------------------------------------------------------
	h.logger.Info("transaction routed",
		zap.String("correlation_id", req.CorrelationID),
		zap.String("merchant_id", req.MerchantID),
		zap.Int64("amount_kopecks", req.Amount),
		// bin_prefix (first 6 digits) is public BIN data — safe to log.
		zap.String("bin_prefix", req.PanFirstSix),
		zap.String("network", result.Network),
		zap.String("source", result.Source),
		zap.Duration("handler_duration", time.Since(start)),
	)

	span.SetAttributes(
		attribute.String("network", result.Network),
		attribute.String("routing_source", result.Source),
	)
	span.SetStatus(codes.Ok, "")

	// --- Respond -------------------------------------------------------------
	resp := RouteResponse{
		CorrelationID:     req.CorrelationID,
		Network:           result.Network,
		ProcessorEndpoint: result.ProcessorEndpoint,
		RoutedAt:          routedAt.UTC().Format(time.RFC3339Nano),
	}

	writeJSON(w, http.StatusOK, resp)
}

// HandleHealth processes GET /health.
// Returns 200 OK with a minimal JSON body so load-balancer probes succeed
// without exposing internal state.
func (h *Handler) HandleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, HealthResponse{
		Status:  "ok",
		Service: "paycore-router",
	})
}

// ---- Helpers ----------------------------------------------------------------

// writeJSON serialises v as JSON and writes it to w with the given status code.
func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// writeError writes a JSON ErrorResponse with the given HTTP status code.
func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, ErrorResponse{Error: msg})
}
