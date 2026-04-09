"""Prometheus metrics for the Fraud Detection service."""

from __future__ import annotations

from prometheus_client import Counter, Histogram

fraud_checks_total = Counter(
    name="fraud_checks_total",
    documentation="Total number of fraud checks performed, labelled by decision",
    labelnames=["decision"],
)

fraud_score_histogram = Histogram(
    name="fraud_score_histogram",
    documentation="Distribution of composite fraud scores (0-2000)",
    buckets=[0, 50, 100, 150, 200, 300, 400, 500, 700, 900, 1200, 1500, 2000],
)

rule_triggers_total = Counter(
    name="rule_triggers_total",
    documentation="Total number of times each rule has been triggered",
    labelnames=["rule"],
)

fraud_check_duration_ms = Histogram(
    name="fraud_check_duration_ms",
    documentation="Time spent processing a fraud check in milliseconds",
    buckets=[1, 5, 10, 20, 40, 60, 80, 100, 150, 200, 500],
)
