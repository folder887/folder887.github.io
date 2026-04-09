package ru.paycore.processing.infrastructure.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import ru.paycore.processing.infrastructure.kafka.dto.AuthorizeCommand;
import ru.paycore.processing.infrastructure.kafka.dto.FraudCheckResult;

import java.time.Duration;

/**
 * Calls the Fraud Detection service via HTTP.
 *
 * Timeout: 80ms — enforced by RestClient read timeout.
 * On timeout or any error: fail-open (approve), log at WARN.
 *
 * This is intentionally fail-open: a slow fraud service must not block
 * the payment flow. False negatives are handled by post-authorization
 * chargeback rules.
 */
@Slf4j
@Component
public class FraudCheckClient {

    private static final Duration TIMEOUT = Duration.ofMillis(80);

    private final RestClient restClient;

    public FraudCheckClient(
        @Value("${paycore.fraud.url:http://fraud-service:8084}") String fraudServiceUrl
    ) {
        this.restClient = RestClient.builder()
            .baseUrl(fraudServiceUrl)
            .build();
    }

    /**
     * Performs a synchronous fraud check with a hard 80ms timeout.
     *
     * @throws never — all errors result in fail-open APPROVE
     */
    public FraudCheckResult checkWithTimeout(AuthorizeCommand command) {
        var requestBody = new FraudRequest(
            command.correlationId().toString(),
            command.panHash(),
            command.amountKopecks(),
            command.mcc(),
            command.merchantId().toString(),
            command.metadata() != null ? command.metadata().getOrDefault("terminal_id", "unknown") : "unknown"
        );

        try {
            var response = restClient.post()
                .uri("/api/v1/fraud/check")
                .body(requestBody)
                .retrieve()
                .toEntity(FraudResponse.class);

            if (response.getBody() == null) {
                log.warn("action=fraud_check_empty_response correlation_id={}", command.correlationId());
                return FraudCheckResult.approve(command.correlationId().toString());
            }

            FraudResponse body = response.getBody();
            boolean declined = "DECLINE".equals(body.decision());

            log.info("action=fraud_check_done correlation_id={} decision={} score={}",
                command.correlationId(), body.decision(), body.score());

            return new FraudCheckResult(
                command.correlationId().toString(),
                declined,
                body.score(),
                body.decision()
            );

        } catch (RestClientException e) {
            // Timeout or connectivity issue — fail-open to protect SLO
            log.warn("action=fraud_check_failed_open correlation_id={} error={}",
                command.correlationId(), e.getMessage());
            return FraudCheckResult.approve(command.correlationId().toString());
        }
    }

    // Internal DTOs — only used by this client

    record FraudRequest(
        String transaction_id,
        String pan_hash,
        long amount_kopecks,
        String mcc,
        String merchant_id,
        String terminal_id
    ) {}

    record FraudResponse(
        String transaction_id,
        String decision,
        int score,
        int ml_score,
        int rules_score,
        double processing_time_ms
    ) {}
}
