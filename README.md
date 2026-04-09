# PayCore — Open Source Payment Processing Infrastructure

[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-blue.svg)](https://openjdk.org/)
[![Go](https://img.shields.io/badge/Go-1.22-00ADD8.svg)](https://go.dev/)
[![Python](https://img.shields.io/badge/Python-3.12-blue.svg)](https://python.org/)

PayCore is a **production-grade payment processing platform** you can self-host or use as a routing layer on top of licensed acquirers (YooKassa, Tinkoff, Stripe). No payment license required in routing mode — real money flows through the acquirer's infrastructure.

---

## Architecture

```
                                   ┌─────────────────────────────────┐
                                   │           YOUR CLIENTS           │
                                   └──────────────┬──────────────────┘
                                                  │ REST API
                                   ┌──────────────▼──────────────────┐
                                   │           GATEWAY :8080          │
                                   │  rate-limit · idempotency · auth │
                                   └──────┬──────────────┬───────────┘
                                          │ Kafka         │ HTTP
                               ┌──────────▼──────┐  ┌────▼────────┐
                               │  PROCESSING :8081│  │ ROUTER :8083│
                               │  state machine   │  │ BIN → acquirer│
                               │  double-entry    │  └─────────────┘
                               └──┬───────────┬──┘
                                  │ HTTP       │ Kafka
                         ┌────────▼──┐  ┌─────▼──────────┐
                         │FRAUD :8084│  │SETTLEMENT :8082 │
                         │ML + rules │  │batch netting    │
                         └───────────┘  └────────────────┘
                                  │
                    ┌─────────────┼─────────────┐
                    ▼             ▼             ▼
              YooKassa        Tinkoff        Stripe
              (licensed)     (licensed)    (licensed)
```

**Key design decisions:**
- All amounts in **kopecks** (integer). Never float.
- Saga pattern via Kafka events. No 2PC.
- Fail-open fraud check (80ms hard timeout).
- Idempotency by key — safe to retry any request.
- Double-entry bookkeeping for every monetary movement.

---

## Services

| Service | Lang | Port | Role |
|---------|------|------|------|
| **gateway** | Java 21 / Spring Boot | 8080 | Public REST API, rate limiting, idempotency |
| **processing** | Java 21 / Spring Boot | 8081 | Core authorization, fraud pre-check, acquirer calls |
| **settlement** | Java 21 / Spring Boot | 8082 | Batch netting, fee calculation, reconciliation |
| **router** | Go 1.22 | 8083 | BIN lookup, acquirer selection, failover rules |
| **fraud** | Python 3.12 / FastAPI | 8084 | ML (ONNX) + rules engine, velocity checks |

**Infrastructure:** PostgreSQL 16, Kafka 7.7, Redis 7, Vault 1.16
**Observability:** Prometheus + Grafana + Jaeger (OpenTelemetry)

---

## Quick Start

```bash
git clone https://github.com/your-org/paycore
cd paycore

# Copy environment config
cp .env.example .env

# Start everything
docker compose up -d

# Wait for healthy (~30s)
docker compose ps

# Test payment (sandbox mode — no real money)
curl -X POST http://localhost:8080/api/v1/payments/authorize \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: test-001" \
  -H "X-Merchant-Id: 018f1234-0000-7000-8000-000000000001" \
  -d '{
    "pan": "4111111111111111",
    "expiryMonth": 12,
    "expiryYear": 2028,
    "cardholderName": "TEST CARD",
    "amountKopecks": 10000,
    "currencyCode": "643",
    "mcc": "5411",
    "transactionType": "PURCHASE"
  }'
```

Expected response:
```json
{
  "transactionId": "...",
  "status": "AUTHORIZED",
  "amountKopecks": 10000,
  "currencyCode": "643",
  "authCode": "750048",
  "rrn": "775376131227",
  "declineReason": null
}
```

---

## Acquirer Routing Mode (No License Required)

Configure real acquirers in `.env`:

```env
# YooKassa sandbox
YOOKASSA_SHOP_ID=100500
YOOKASSA_SECRET_KEY=test_your_key_here
YOOKASSA_BASE_URL=https://api.yookassa.ru/v3

# Tinkoff Kassa sandbox
TINKOFF_TERMINAL_KEY=TinkoffBankTest
TINKOFF_SECRET_KEY=TinkoffBankTest
TINKOFF_BASE_URL=https://securepay.tinkoff.ru/v2

# Routing mode: ACQUIRER (real) or INTERNAL (sandbox ledger)
PAYCORE_PROCESSING_MODE=ACQUIRER
```

In `ACQUIRER` mode, PayCore forwards authorizations to the configured acquirer. You never touch money directly — the licensed provider does. PayCore handles:
- Unified API across acquirers
- Smart routing (lowest cost, highest reliability)
- Failover (YooKassa down → Tinkoff)
- Fraud pre-screening
- Settlement reconciliation

---

## Payment Flow (Sequence)

```
Client → Gateway:     POST /authorize {pan, amount, mcc}
Gateway → Redis:      Check idempotency key
Gateway → Kafka:      Publish payment.commands
Processing → Fraud:   POST /fraud/check (80ms timeout, fail-open)
Processing → Router:  GET /route {bin, mcc, amount}
Processing → Acquirer: POST /payments (YooKassa or Tinkoff)
Acquirer → Processing: {status: succeeded, authCode: "123456"}
Processing → Kafka:   Publish payment.replies
Gateway → Client:     {status: AUTHORIZED, authCode: "123456"}
```

---

## Monitoring

| URL | Tool | Credentials |
|-----|------|-------------|
| http://localhost:3000 | Grafana | admin/admin |
| http://localhost:9090 | Prometheus | — |
| http://localhost:16686 | Jaeger | — |

---

## API Reference

### POST /api/v1/payments/authorize

**Headers:**
- `Content-Type: application/json`
- `Idempotency-Key: <uuid>` — safe retry key
- `X-Merchant-Id: <uuid>` — merchant identifier

**Request:**
```json
{
  "pan": "4111111111111111",
  "expiryMonth": 12,
  "expiryYear": 2028,
  "cardholderName": "IVAN PETROV",
  "amountKopecks": 150000,
  "currencyCode": "643",
  "mcc": "5411",
  "transactionType": "PURCHASE",
  "metadata": {
    "orderId": "ORD-12345"
  }
}
```

**Response codes:**
- `201` — AUTHORIZED
- `402` — DECLINED (insufficient funds, fraud, etc.)
- `400` — Invalid request
- `429` — Rate limit exceeded (100 RPS/merchant)
- `500` — Processor unavailable

---

## Business Model

PayCore is free and open-source (MIT). We offer:

| Tier | Price | What you get |
|------|-------|-------------|
| **Self-hosted** | Free | Full source, run anywhere |
| **Managed Cloud** | $299/mo | Hosted, monitored, SLA 99.9% |
| **Enterprise** | Custom | Dedicated infra, support, SLA 99.99% |

---

## Contributing

```bash
# Run tests
mvn test -pl services/gateway,services/processing,services/settlement
cd services/fraud && pytest

# Code style
cd services/router && go vet ./...
cd services/fraud && ruff check src/
```

PRs welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

---

## License

MIT — see [LICENSE](LICENSE). Use freely, commercially or otherwise.
