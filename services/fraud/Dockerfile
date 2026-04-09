FROM python:3.12-slim AS base
WORKDIR /app

# Install dependencies
COPY services/fraud/pyproject.toml ./
RUN pip install --no-cache-dir --timeout 120 ".[dev]" 2>/dev/null || pip install --no-cache-dir --timeout 120 .

# Copy source
COPY services/fraud/src ./src

ENV PYTHONPATH=/app/src
ENV PYTHONUNBUFFERED=1

RUN addgroup --system paycore && adduser --system --ingroup paycore paycore
USER paycore

EXPOSE 8084
HEALTHCHECK --interval=10s --timeout=5s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8084/health')"

CMD ["python", "-m", "fraud.main"]
