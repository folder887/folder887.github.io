"""Pydantic models for the Fraud Detection service."""

from __future__ import annotations

import uuid
from enum import Enum

from pydantic import BaseModel, Field


class FraudDecision(str, Enum):
    APPROVE = "APPROVE"
    REVIEW = "REVIEW"
    DECLINE = "DECLINE"


class FraudCheckRequest(BaseModel):
    """Incoming fraud check request.

    All monetary amounts are in kopecks (integer). No float values.
    """

    transaction_id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    pan_hash: str = Field(..., description="SHA-256 hash of the card PAN")
    amount_kopecks: int = Field(..., gt=0, description="Transaction amount in kopecks")
    mcc: str = Field(..., min_length=4, max_length=4, description="Merchant Category Code")
    merchant_id: str = Field(..., description="Merchant identifier")
    terminal_id: str = Field(..., description="Terminal identifier")
    # ISO-8601 UTC timestamp; if omitted, server time is used
    transaction_ts: str | None = Field(
        default=None,
        description="Transaction timestamp in ISO-8601 UTC format",
    )


class RuleTrigger(BaseModel):
    """Details of a single rule that fired during evaluation."""

    rule_name: str
    score_contribution: int
    triggered: bool


class FraudCheckResponse(BaseModel):
    """Result of a fraud check."""

    transaction_id: str
    decision: FraudDecision
    score: int = Field(..., ge=0, le=2000, description="Composite fraud score (0 = clean)")
    ml_score: int = Field(..., ge=0, le=1000, description="ONNX model score contribution")
    rules_score: int = Field(..., ge=0, description="Rules engine score contribution")
    triggered_rules: list[RuleTrigger]
    processing_time_ms: float
