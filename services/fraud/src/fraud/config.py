"""Service configuration loaded from environment variables."""

from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="FRAUD_", env_file=".env", extra="ignore")

    # HTTP server
    host: str = "0.0.0.0"
    port: int = 8084

    # Fraud decision thresholds
    score_approve_threshold: int = 300   # score < 300 → APPROVE
    score_review_threshold: int = 700   # score 300-699 → REVIEW, ≥700 → DECLINE

    # Timing
    check_timeout_ms: int = 80

    # Redis
    redis_url: str = "redis://localhost:6379/0"
    redis_blocklist_key: str = "fraud:blocklist"
    redis_velocity_prefix: str = "fraud:velocity:"

    # Kafka
    kafka_bootstrap_servers: str = "localhost:9092"
    kafka_consumer_group: str = "paycore-fraud"
    kafka_topic_requests: str = "payment.fraud.requests"
    kafka_topic_results: str = "payment.fraud.checked"

    # ONNX model path (optional)
    onnx_model_path: str = "/opt/fraud/model.onnx"

    # Velocity rule window
    velocity_window_seconds: int = 60
    velocity_threshold: int = 3

    # Logging
    log_level: str = "INFO"
    log_json: bool = True

    # OpenTelemetry
    otel_endpoint: str = ""
    service_name: str = "paycore-fraud"


settings = Settings()
