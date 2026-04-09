"""
PayCore Fraud Detection Service — entry point.

Runs two concurrent components:
  1. FastAPI HTTP server (uvicorn) — synchronous fraud check endpoint
     for Processing Core's 80ms timeout path (HTTP is faster than Kafka round-trip)
  2. Kafka consumer loop — async batch fraud checks for analytics/review queue

Usage:
    python -m fraud.main
    # or via uvicorn for HTTP-only mode:
    uvicorn fraud.main:app --host 0.0.0.0 --port 8084
"""

from __future__ import annotations

import asyncio
import logging
import os
import signal
import threading
import time
from contextlib import asynccontextmanager
from typing import AsyncGenerator

import structlog
import uvicorn
from fastapi import FastAPI, HTTPException, status
from fastapi.responses import JSONResponse
from prometheus_client import make_asgi_app

from fraud.config import settings
from fraud.kafka import FraudKafkaConsumer
from fraud.metrics import fraud_check_duration_ms, fraud_checks_total
from fraud.ml.onnx_scorer import OnnxScorer
from fraud.models import FraudCheckRequest, FraudCheckResponse
from fraud.rules.engine import RulesEngine

# ---------------------------------------------------------------------------
# Structured logging setup
# ---------------------------------------------------------------------------
structlog.configure(
    processors=[
        structlog.contextvars.merge_contextvars,
        structlog.processors.add_log_level,
        structlog.processors.TimeStamper(fmt="iso"),
        structlog.processors.JSONRenderer() if settings.log_json
        else structlog.dev.ConsoleRenderer(),
    ],
    wrapper_class=structlog.make_filtering_bound_logger(
        logging.getLevelName(settings.log_level.upper())
    ),
)

log: structlog.BoundLogger = structlog.get_logger(__name__)

# ---------------------------------------------------------------------------
# Shared singletons (initialised once at startup)
# ---------------------------------------------------------------------------
_rules_engine: RulesEngine | None = None
_ml_scorer: OnnxScorer | None = None
_redis_client = None
_kafka_consumer: FraudKafkaConsumer | None = None
_kafka_thread: threading.Thread | None = None


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    """Initialise shared resources on startup, clean up on shutdown."""
    global _rules_engine, _ml_scorer, _redis_client, _kafka_consumer, _kafka_thread

    import redis.asyncio as aio_redis

    _rules_engine = RulesEngine()
    _ml_scorer = OnnxScorer(settings.onnx_model_path)
    _redis_client = aio_redis.from_url(settings.redis_url, decode_responses=True)

    # Kafka consumer runs in a daemon thread so it doesn't block the event loop
    _kafka_consumer = FraudKafkaConsumer()
    _kafka_thread = threading.Thread(
        target=_kafka_consumer.start,
        name="fraud-kafka-consumer",
        daemon=True,
    )
    _kafka_thread.start()
    log.info("fraud_service_started", port=settings.port)

    yield

    # Shutdown
    if _kafka_consumer:
        _kafka_consumer.stop()
    if _redis_client:
        await _redis_client.aclose()
    log.info("fraud_service_stopped")


# ---------------------------------------------------------------------------
# FastAPI application
# ---------------------------------------------------------------------------
app = FastAPI(
    title="PayCore Fraud Detection",
    version="1.0.0",
    lifespan=lifespan,
)

# Prometheus metrics endpoint mounted at /metrics
metrics_app = make_asgi_app()
app.mount("/metrics", metrics_app)


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok", "service": settings.service_name}


@app.post("/api/v1/fraud/check", response_model=FraudCheckResponse)
async def fraud_check(request: FraudCheckRequest) -> FraudCheckResponse:
    """
    Synchronous fraud check — called by Processing Core with 80ms timeout.

    Returns APPROVE / REVIEW / DECLINE decision with composite score.
    """
    if _rules_engine is None or _ml_scorer is None or _redis_client is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Service not initialised",
        )

    start_ms = time.monotonic() * 1000

    import datetime as _dt
    rules_score, triggers = await _rules_engine.evaluate(request, _redis_client)
    ml_features = {
        "amount_kopecks": request.amount_kopecks,
        "hour_of_day": _dt.datetime.now(_dt.timezone.utc).hour,
        "is_new_merchant": 0,
        "velocity_1min": 0,
        "velocity_5min": 0,
    }
    ml_score = _ml_scorer.score(ml_features)

    total_score = rules_score + ml_score
    decision = RulesEngine.make_decision(total_score)

    elapsed_ms = time.monotonic() * 1000 - start_ms
    fraud_check_duration_ms.observe(elapsed_ms)
    fraud_checks_total.labels(decision=decision.value).inc()

    log.info("http_fraud_check_complete",
             transaction_id=request.transaction_id,
             score=total_score,
             decision=decision.value,
             elapsed_ms=round(elapsed_ms, 2))

    return FraudCheckResponse(
        transaction_id=request.transaction_id,
        decision=decision,
        score=total_score,
        ml_score=ml_score,
        rules_score=rules_score,
        triggered_rules=triggers,
        processing_time_ms=round(elapsed_ms, 2),
    )


# ---------------------------------------------------------------------------
# Standalone execution
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    uvicorn.run(
        "fraud.main:app",
        host=settings.host,
        port=settings.port,
        log_config=None,  # structlog handles logging
        access_log=False,
    )
