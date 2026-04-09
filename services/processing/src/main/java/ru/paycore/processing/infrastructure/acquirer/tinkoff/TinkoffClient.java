package ru.paycore.processing.infrastructure.acquirer.tinkoff;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.paycore.processing.infrastructure.acquirer.AcquirerPort;
import ru.paycore.processing.infrastructure.acquirer.AcquirerRequest;
import ru.paycore.processing.infrastructure.acquirer.AcquirerResult;
import ru.paycore.processing.infrastructure.acquirer.tinkoff.dto.TinkoffInitRequest;
import ru.paycore.processing.infrastructure.acquirer.tinkoff.dto.TinkoffInitResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

/**
 * Tinkoff Kassa acquirer adapter.
 *
 * Uses Tinkoff Acquiring API v2. Auth: HMAC-SHA256 token signature.
 * Sandbox: TerminalKey=TinkoffBankTest, SecretKey=TinkoffBankTest
 *
 * Key properties:
 *   paycore.acquirer.tinkoff.terminal-key
 *   paycore.acquirer.tinkoff.secret-key
 *   paycore.acquirer.tinkoff.base-url
 *   paycore.acquirer.tinkoff.enabled = true
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "paycore.acquirer.tinkoff.enabled", havingValue = "true")
public class TinkoffClient implements AcquirerPort {

    private final RestClient restClient;
    private final String terminalKey;
    private final String secretKey;

    public TinkoffClient(
        @Value("${paycore.acquirer.tinkoff.terminal-key}") String terminalKey,
        @Value("${paycore.acquirer.tinkoff.secret-key}") String secretKey,
        @Value("${paycore.acquirer.tinkoff.base-url:https://securepay.tinkoff.ru/v2}") String baseUrl
    ) {
        this.terminalKey = terminalKey;
        this.secretKey = secretKey;
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(10_000);
        requestFactory.setReadTimeout(30_000);
        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory)
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .build();
    }

    @Override
    public String name() {
        return "tinkoff";
    }

    @Override
    public AcquirerResult authorize(AcquirerRequest request) {
        var orderId = request.correlationId().toString();
        var description = "Order %s".formatted(orderId);
        var token = generateToken(terminalKey, request.amountKopecks(), orderId, description);

        var body = new TinkoffInitRequest(
            terminalKey,
            request.amountKopecks(),
            orderId,
            description,
            "ru",
            token
        );

        try {
            var response = restClient.post()
                .uri("/Init")
                .body(body)
                .retrieve()
                .body(TinkoffInitResponse.class);

            if (response == null) {
                return AcquirerResult.error("Empty response from Tinkoff", name());
            }

            if (response.isAuthorized()) {
                // In sandbox, Tinkoff returns PaymentId as auth code equivalent
                var authCode = "%06d".formatted(Math.abs(response.paymentId().hashCode() % 999999));
                log.info("action=tinkoff_authorized correlation_id={} payment_id={}",
                    request.correlationId(), response.paymentId());
                return AcquirerResult.authorized(response.paymentId(), authCode, name());
            } else {
                log.info("action=tinkoff_declined correlation_id={} error_code={} message={}",
                    request.correlationId(), response.errorCode(), response.message());
                return AcquirerResult.declined(response.declineCode(), response.message(), name());
            }

        } catch (Exception e) {
            log.error("action=tinkoff_error correlation_id={}", request.correlationId(), e);
            return AcquirerResult.error(e.getMessage(), name());
        }
    }

    /**
     * Tinkoff token = HMAC-SHA256 of sorted concatenated values.
     * Docs: https://www.tinkoff.ru/kassa/develop/api/request-sign/
     */
    private String generateToken(String terminalKey, long amount, String orderId, String description) {
        var params = new TreeMap<String, String>();
        params.put("Amount", String.valueOf(amount));
        params.put("Description", description);
        params.put("OrderId", orderId);
        params.put("Password", secretKey);  // secretKey is called Password in token generation
        params.put("TerminalKey", terminalKey);

        var concatenated = String.join("", params.values());

        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var hash = mac.doFinal(concatenated.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Cannot generate Tinkoff token", e);
        }
    }
}
