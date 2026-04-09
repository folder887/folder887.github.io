.PHONY: up down build migrate test lint clean

# ─── Local dev ────────────────────────────────────────────────────────────────
up:
	docker compose up -d postgres kafka redis vault jaeger prometheus grafana
	@echo "Infrastructure ready. Run 'make migrate' then 'make services'"

services:
	docker compose up -d gateway processing settlement router fraud

down:
	docker compose down

logs:
	docker compose logs -f --tail=100

# ─── Build ────────────────────────────────────────────────────────────────────
build:
	$(MAKE) build-java
	$(MAKE) build-go
	$(MAKE) build-python

build-java:
	cd services/gateway    && mvn -q package -DskipTests
	cd services/processing && mvn -q package -DskipTests
	cd services/settlement && mvn -q package -DskipTests

build-go:
	cd services/router && go build -o bin/router ./cmd/router

build-python:
	cd services/fraud && pip install -e ".[dev]" -q

# ─── Database ─────────────────────────────────────────────────────────────────
migrate:
	docker compose run --rm -e FLYWAY_URL=jdbc:postgresql://postgres:5432/paycore \
		-e FLYWAY_USER=paycore \
		-e FLYWAY_PASSWORD=$${POSTGRES_PASSWORD:-secret} \
		flyway:10-alpine migrate

migrate-info:
	docker compose run --rm flyway:10-alpine info

# ─── Tests ────────────────────────────────────────────────────────────────────
test:
	$(MAKE) test-java
	$(MAKE) test-go
	$(MAKE) test-python

test-java:
	cd services/gateway    && mvn test
	cd services/processing && mvn test
	cd services/settlement && mvn test

test-go:
	cd services/router && go test ./... -race -count=1

test-python:
	cd services/fraud && pytest --tb=short -q

# ─── Load test ────────────────────────────────────────────────────────────────
load-test:
	k6 run scripts/load/payment_flow.js --vus 100 --duration 60s

# ─── Lint ─────────────────────────────────────────────────────────────────────
lint:
	cd services/gateway    && mvn checkstyle:check
	cd services/processing && mvn checkstyle:check
	cd services/settlement && mvn checkstyle:check
	cd services/router     && golangci-lint run ./...
	cd services/fraud      && ruff check . && mypy .

# ─── Clean ────────────────────────────────────────────────────────────────────
clean:
	cd services/gateway    && mvn clean
	cd services/processing && mvn clean
	cd services/settlement && mvn clean
	cd services/router     && rm -rf bin/
	docker compose down -v
