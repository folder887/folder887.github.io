package ru.paycore.processing.infrastructure.acquirer.yookassa;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import ru.paycore.processing.infrastructure.acquirer.AcquirerPort;
import ru.paycore.processing.infrastructure.acquirer.AcquirerRequest;
import ru.paycore.processing.infrastructure.acquirer.AcquirerResult;
import ru.paycore.processing.infrastructure.acquirer.yookassa.dto.YooKassaPaymentRequest;
import ru.paycore.processing.infrastructure.acquirer.yookassa.dto.YooKassaPaymentResponse;

import java.util.Map;

/**
 * YooKassa acquirer adapter.
 *
 * Uses YooKassa API v3. Auth: HTTP Basic (shopId:secretKey).
 * Sandbox: set YOOKASSA_BASE_URL=https://api.yookassa.ru/v3 and use test credentials.
 *
 * Payment flow:
 *   1. Create payment with confirmation.type=redirect
 *   2. YooKassa returns pending status + confirmation_url
 *   3. We treat "pending" as authorized (payment initiated)
 *   4. In real flow, customer is redirected to confirmation_url to complete payment
 *
 * Key properties (application.yml or env):
 *   paycore.acquirer.yookassa.shop-id
 *   paycore.acquirer.yookassa.secret-key
 *   paycore.acquirer.yookassa.base-url
 *   paycore.acquirer.yookassa.enabled = true
 *   paycore.acquirer.yookassa.return-url  (redirect URL after payment)
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "paycore.acquirer.yookassa.enabled", havingValue = "true")
public class YooKassaClient implements AcquirerPort {

    private final RestClient restClient;
    private final String shopId;
    private final String returnUrl;

    public YooKassaClient(
        @Value("${paycore.acquirer.yookassa.shop-id}") String shopId,
        @Value("${paycore.acquirer.yookassa.secret-key}") String secretKey,
        @Value("${paycore.acquirer.yookassa.base-url:https://api.yookassa.ru/v3}") String baseUrl,
        @Value("${paycore.acquirer.yookassa.return-url:https://paycore.dev/payment/return}") String returnUrl
    ) {
        this.shopId = shopId;
        this.returnUrl = returnUrl;

        // Use SimpleClientHttpRequestFactory (HttpURLConnection) — avoids JDK HTTP/2 negotiation issues
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(10_000);
        requestFactory.setReadTimeout(30_000);

        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory)
            .defaultHeader("Authorization", basicAuth(shopId, secretKey))
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .build();
    }

    @Override
    public String name() {
        return "yookassa";
    }

    @Override
    public AcquirerResult authorize(AcquirerRequest request) {
        var body = new YooKassaPaymentRequest(
            new YooKassaPaymentRequest.Amount(
                request.amountRubles(),
                YooKassaPaymentRequest.toCurrencyCode(request.currencyCode())
            ),
            true,  // capture immediately
            "Payment %s".formatted(request.correlationId()),
            Map.of(
                "correlation_id", request.correlationId().toString(),
                "merchant_id",    request.merchantId().toString(),
                "mcc",            request.mcc()
            ),
            new YooKassaPaymentRequest.Confirmation("redirect", returnUrl)
        );

        try {
            var response = restClient.post()
                .uri("/payments")
                // Idempotence-Key — YooKassa deduplicates by this header (note: no 'y')
                .header("Idempotence-Key", request.correlationId().toString())
                .body(body)
                .retrieve()
                .body(YooKassaPaymentResponse.class);

            if (response == null) {
                return AcquirerResult.error("Empty response from YooKassa", name());
            }

            if (response.isAuthorized()) {
                log.info("action=yookassa_authorized correlation_id={} external_id={} status={}",
                    request.correlationId(), response.id(), response.status());
                return AcquirerResult.authorized(response.id(), response.authCode(), name());
            } else {
                log.info("action=yookassa_declined correlation_id={} reason={}",
                    request.correlationId(), response.declineReason());
                return AcquirerResult.declined(response.declineReason(), response.declineReason(), name());
            }

        } catch (HttpClientErrorException e) {
            log.warn("action=yookassa_http_error correlation_id={} status={} body={}",
                request.correlationId(), e.getStatusCode(), e.getResponseBodyAsString());
            return AcquirerResult.error("YooKassa HTTP %s".formatted(e.getStatusCode()), name());
        } catch (Exception e) {
            log.error("action=yookassa_error correlation_id={}", request.correlationId(), e);
            return AcquirerResult.error(e.getMessage(), name());
        }
    }

    private static String basicAuth(String user, String password) {
        var credentials = user + ":" + password;
        return "Basic " + java.util.Base64.getEncoder().encodeToString(credentials.getBytes());
    }
}
