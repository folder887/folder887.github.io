"""Rules engine for PayCore Fraud Detection service.

Each rule is a callable that receives a RuleContext and returns a score
contribution (int). Rules that fire also increment the Prometheus counter
`rule_triggers_total`.

Decision thresholds (configurable via Settings):
  score < 300   → APPROVE
  300 ≤ score < 700 → REVIEW
  score ≥ 700   → DECLINE
"""

from __future__ import annotations

import datetime
import logging
from dataclasses import dataclass, field
from typing import Callable, Protocol

import redis.asyncio as aio_redis
import structlog

from fraud.config import settings
from fraud.metrics import rule_triggers_total
from fraud.models import FraudDecision, FraudCheckRequest, RuleTrigger

log: structlog.BoundLogger = structlog.get_logger(__name__)


# ---------------------------------------------------------------------------
# Rule context
# ---------------------------------------------------------------------------

@dataclass
class RuleContext:
    """Enriched context passed to every rule during evaluation."""

    request: FraudCheckRequest
    redis: aio_redis.Redis  # type: ignore[type-arg]
    transaction_dt: datetime.datetime  # always UTC
    velocity_1min: int = 0
    velocity_5min: int = 0
    is_new_merchant: bool = False
    # Populated by BlocklistRule before returning; used to short-circuit
    immediate_decline: bool = False


# ---------------------------------------------------------------------------
# Rule protocol
# ---------------------------------------------------------------------------

class Rule(Protocol):
    """A single fraud rule.

    Returns (score_contribution, triggered).
    """

    name: str

    async def evaluate(self, ctx: RuleContext) -> tuple[int, bool]:
        ...


# ---------------------------------------------------------------------------
# Concrete rules
# ---------------------------------------------------------------------------

class VelocityRule:
    """More than `threshold` transactions from the same card in 60 s → +400."""

    name = "VelocityRule"
    score_contribution = 400

    def __init__(self) -> None:
        self._threshold = settings.velocity_threshold
        self._window = settings.velocity_window_seconds

    async def evaluate(self, ctx: RuleContext) -> tuple[int, bool]:
        # ctx.velocity_1min is pre-populated by the engine
        triggered = ctx.velocity_1min > self._threshold
        if triggered:
            rule_triggers_total.labels(rule=self.name).inc()
            log.debug("rule_triggered", rule=self.name, velocity=ctx.velocity_1min)
        return (self.score_contribution if triggered else 0, triggered)


class AmountRule:
    """Amount > 500 000 kopecks (5 000 RUB) → +200."""

    name = "AmountRule"
    score_contribution = 200
    THRESHOLD_KOPECKS: int = 500_000

    async def evaluate(self, ctx: RuleContext) -> tuple[int, bool]:
        triggered = ctx.request.amount_kopecks > self.THRESHOLD_KOPECKS
        if triggered:
            rule_triggers_total.labels(rule=self.name).inc()
            log.debug(
                "rule_triggered",
                rule=self.name,
                amount_kopecks=ctx.request.amount_kopecks,
            )
        return (self.score_contribution if triggered else 0, triggered)


class NightTimeRule:
    """Transaction between 02:00 and 05:00 Moscow time → +100."""

    name = "NightTimeRule"
    score_contribution = 100
    # Moscow is UTC+3
    MSK_OFFSET = datetime.timezone(datetime.timedelta(hours=3))
    NIGHT_START = 2   # 02:00
    NIGHT_END = 5     # 05:00 (exclusive)

    async def evaluate(self, ctx: RuleContext) -> tuple[int, bool]:
        msk_dt = ctx.transaction_dt.astimezone(self.MSK_OFFSET)
        triggered = self.NIGHT_START <= msk_dt.hour < self.NIGHT_END
        if triggered:
            rule_triggers_total.labels(rule=self.name).inc()
            log.debug("rule_triggered", rule=self.name, msk_hour=msk_dt.hour)
        return (self.score_contribution if triggered else 0, triggered)


class NewMerchantRule:
    """First transaction with this MCC for the given card → +150."""

    name = "NewMerchantRule"
    score_contribution = 150

    async def evaluate(self, ctx: RuleContext) -> tuple[int, bool]:
        triggered = ctx.is_new_merchant
        if triggered:
            rule_triggers_total.labels(rule=self.name).inc()
            log.debug(
                "rule_triggered",
                rule=self.name,
                pan_hash=ctx.request.pan_hash[:8] + "...",
                mcc=ctx.request.mcc,
            )
        return (self.score_contribution if triggered else 0, triggered)


class BlocklistRule:
    """pan_hash found in Redis blocklist → +1000 (immediate DECLINE)."""

    name = "BlocklistRule"
    score_contribution = 1000

    async def evaluate(self, ctx: RuleContext) -> tuple[int, bool]:
        is_blocked: bool = await ctx.redis.sismember(  # type: ignore[assignment]
            settings.redis_blocklist_key,
            ctx.request.pan_hash,
        )
        if is_blocked:
            ctx.immediate_decline = True
            rule_triggers_total.labels(rule=self.name).inc()
            log.warning(
                "blocklist_hit",
                rule=self.name,
                pan_hash=ctx.request.pan_hash[:8] + "...",
            )
        return (self.score_contribution if is_blocked else 0, is_blocked)


# ---------------------------------------------------------------------------
# Engine
# ---------------------------------------------------------------------------

@dataclass
class RulesEngine:
    """Evaluates all rules in order and aggregates the score."""

    rules: list[VelocityRule | AmountRule | NightTimeRule | NewMerchantRule | BlocklistRule] = field(
        default_factory=lambda: [
            BlocklistRule(),   # run first — allows immediate short-circuit
            VelocityRule(),
            AmountRule(),
            NightTimeRule(),
            NewMerchantRule(),
        ]
    )

    async def evaluate(
        self,
        request: FraudCheckRequest,
        redis: aio_redis.Redis,  # type: ignore[type-arg]
    ) -> tuple[int, list[RuleTrigger]]:
        """Run all rules and return (total_score, list[RuleTrigger])."""

        ctx = await self._build_context(request, redis)

        total_score = 0
        triggers: list[RuleTrigger] = []

        for rule in self.rules:
            contribution, triggered = await rule.evaluate(ctx)
            total_score += contribution
            triggers.append(
                RuleTrigger(
                    rule_name=rule.name,
                    score_contribution=contribution,
                    triggered=triggered,
                )
            )
            # Blocklist → no need to run further rules
            if ctx.immediate_decline:
                break

        log.info(
            "rules_evaluated",
            transaction_id=request.transaction_id,
            rules_score=total_score,
            triggered=[t.rule_name for t in triggers if t.triggered],
        )
        return total_score, triggers

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    async def _build_context(
        self,
        request: FraudCheckRequest,
        redis: aio_redis.Redis,  # type: ignore[type-arg]
    ) -> RuleContext:
        """Enrich the rule context with Redis-backed data."""

        if request.transaction_ts:
            try:
                transaction_dt = datetime.datetime.fromisoformat(
                    request.transaction_ts.replace("Z", "+00:00")
                )
            except ValueError:
                transaction_dt = datetime.datetime.now(datetime.timezone.utc)
        else:
            transaction_dt = datetime.datetime.now(datetime.timezone.utc)

        # Velocity: count recent transactions for this pan_hash
        velocity_key = f"{settings.redis_velocity_prefix}{request.pan_hash}"
        pipe = redis.pipeline()
        now_ts = int(transaction_dt.timestamp())
        window_start = now_ts - settings.velocity_window_seconds
        five_min_start = now_ts - 300

        pipe.zremrangebyscore(velocity_key, "-inf", window_start - 1)
        pipe.zadd(velocity_key, {str(now_ts): now_ts})
        pipe.expire(velocity_key, settings.velocity_window_seconds * 2)
        pipe.zcount(velocity_key, window_start, "+inf")
        pipe.zcount(velocity_key, five_min_start, "+inf")
        results = await pipe.execute()

        velocity_1min: int = int(results[3])
        velocity_5min: int = int(results[4])

        # New merchant check: has this card ever used this MCC?
        mcc_key = f"fraud:mcc:{request.pan_hash}:{request.mcc}"
        is_new_merchant = not bool(await redis.exists(mcc_key))
        if is_new_merchant:
            # Mark as seen (TTL = 90 days)
            await redis.setex(mcc_key, 90 * 86400, "1")

        return RuleContext(
            request=request,
            redis=redis,
            transaction_dt=transaction_dt,
            velocity_1min=velocity_1min,
            velocity_5min=velocity_5min,
            is_new_merchant=is_new_merchant,
        )

    @staticmethod
    def make_decision(score: int) -> FraudDecision:
        """Map a composite score to a FraudDecision."""
        if score < settings.score_approve_threshold:
            return FraudDecision.APPROVE
        if score < settings.score_review_threshold:
            return FraudDecision.REVIEW
        return FraudDecision.DECLINE
