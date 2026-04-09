"""Kafka consumer/producer for the Fraud Detection service."""

from __future__ import annotations

import asyncio
import json
import time
from typing import Any

import structlog
from kafka import KafkaConsumer, KafkaProducer
from kafka.errors import KafkaError

from fraud.config import settings
from fraud.metrics import fraud_check_duration_ms, fraud_checks_total
from fraud.models import FraudCheckRequest
from fraud.rules.engine import RulesEngine
from fraud.ml.onnx_scorer import OnnxScorer

log: structlog.BoundLogger = structlog.get_logger(__name__)


class FraudKafkaConsumer:
    """
    Consumes fraud check requests from Kafka, evaluates them, publishes results.

    Topics:
      IN:  payment.fraud.requests  — AuthorizeCommand payload from Processing Core
      OUT: payment.fraud.checked   — FraudCheckResponse JSON
    """

    def __init__(self) -> None:
        self._rules_engine = RulesEngine()
        self._ml_scorer = OnnxScorer(settings.onnx_model_path)
        self._consumer: KafkaConsumer | None = None
        self._producer: KafkaProducer | None = None
        self._running = False

    def start(self) -> None:
        """Build Kafka clients and start the blocking consume loop."""
        self._consumer = KafkaConsumer(
            settings.kafka_topic_requests,
            bootstrap_servers=settings.kafka_bootstrap_servers,
            group_id=settings.kafka_consumer_group,
            auto_offset_reset="earliest",
            enable_auto_commit=False,
            value_deserializer=lambda b: json.loads(b.decode("utf-8")),
            key_deserializer=lambda b: b.decode("utf-8") if b else None,
            max_poll_records=50,
            session_timeout_ms=10_000,
            heartbeat_interval_ms=3_000,
        )
        self._producer = KafkaProducer(
            bootstrap_servers=settings.kafka_bootstrap_servers,
            value_serializer=lambda v: json.dumps(v).encode("utf-8"),
            key_serializer=lambda k: k.encode("utf-8") if k else None,
            acks="all",
            retries=3,
        )

        self._running = True
        log.info("kafka_consumer_started",
                 topic=settings.kafka_topic_requests,
                 group=settings.kafka_consumer_group)
        self._consume_loop()

    def stop(self) -> None:
        self._running = False
        if self._consumer:
            self._consumer.close()
        if self._producer:
            self._producer.flush(timeout=5)
            self._producer.close()
        log.info("kafka_consumer_stopped")

    def _consume_loop(self) -> None:
        import redis.asyncio as aio_redis

        redis_client = aio_redis.from_url(settings.redis_url, decode_responses=True)
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)

        assert self._consumer is not None
        assert self._producer is not None

        try:
            for message in self._consumer:
                if not self._running:
                    break
                try:
                    result = loop.run_until_complete(
                        self._handle(message.value, redis_client)
                    )
                    self._publish(message.key or result["transaction_id"], result)
                    self._consumer.commit()
                except Exception as exc:
                    log.error("fraud_check_error",
                              offset=message.offset,
                              error=str(exc),
                              exc_info=True)
                    self._consumer.commit()
        finally:
            loop.run_until_complete(redis_client.aclose())
            loop.close()

    async def _handle(self, raw: dict[str, Any], redis_client: Any) -> dict[str, Any]:
        start_ms = time.monotonic() * 1000

        # Support both camelCase (from Java) and snake_case field names
        request = FraudCheckRequest(
            transaction_id=raw.get("correlationId") or raw.get("transaction_id", ""),
            pan_hash=raw.get("panHash") or raw.get("pan_hash", ""),
            amount_kopecks=int(raw.get("amountKopecks") or raw.get("amount_kopecks", 0)),
            mcc=raw.get("mcc", "0000"),
            merchant_id=str(raw.get("merchantId") or raw.get("merchant_id", "")),
            terminal_id=str(raw.get("terminalId") or raw.get("terminal_id", "unknown")),
            transaction_ts=raw.get("sentAt") or raw.get("transaction_ts"),
        )

        import datetime as _dt
        rules_score, triggers = await self._rules_engine.evaluate(request, redis_client)
        ml_features = {
            "amount_kopecks": request.amount_kopecks,
            "hour_of_day": _dt.datetime.now(_dt.timezone.utc).hour,
            "is_new_merchant": 0,
            "velocity_1min": 0,
            "velocity_5min": 0,
        }
        ml_score = self._ml_scorer.score(ml_features)

        total_score = rules_score + ml_score
        decision = RulesEngine.make_decision(total_score)

        elapsed_ms = time.monotonic() * 1000 - start_ms
        fraud_check_duration_ms.observe(elapsed_ms)
        fraud_checks_total.labels(decision=decision.value).inc()

        log.info("fraud_check_complete",
                 transaction_id=request.transaction_id,
                 score=total_score,
                 decision=decision.value,
                 elapsed_ms=round(elapsed_ms, 2))

        return {
            "transaction_id": request.transaction_id,
            "decision": decision.value,
            "score": total_score,
            "ml_score": ml_score,
            "rules_score": rules_score,
            "triggered_rules": [
                {
                    "rule_name": t.rule_name,
                    "score_contribution": t.score_contribution,
                    "triggered": t.triggered,
                }
                for t in triggers
            ],
            "processing_time_ms": round(elapsed_ms, 2),
        }

    def _publish(self, key: str, result: dict[str, Any]) -> None:
        assert self._producer is not None
        try:
            self._producer.send(settings.kafka_topic_results, key=key, value=result)
        except KafkaError as exc:
            log.error("fraud_result_publish_failed",
                      transaction_id=key,
                      error=str(exc))
