FROM golang:1.22-alpine AS build
WORKDIR /app
COPY services/router/go.mod services/router/go.sum ./
RUN go mod download
COPY services/router/ .
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o bin/router ./cmd/router

FROM alpine:3.20
RUN apk add --no-cache wget && addgroup -S paycore && adduser -S paycore -G paycore
COPY --from=build /app/bin/router /usr/local/bin/router
USER paycore
EXPOSE 8083
HEALTHCHECK --interval=10s --timeout=5s --retries=3 \
    CMD wget -qO- http://localhost:8083/health || exit 1
ENTRYPOINT ["router"]
