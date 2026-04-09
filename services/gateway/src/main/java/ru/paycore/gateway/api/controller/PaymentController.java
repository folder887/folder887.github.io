package ru.paycore.gateway.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.paycore.gateway.api.dto.AuthorizeRequestDto;
import ru.paycore.gateway.api.dto.PaymentResponseDto;
import ru.paycore.gateway.api.mapper.PaymentMapper;
import ru.paycore.gateway.app.port.PaymentGatewayPort;
import ru.paycore.gateway.infrastructure.security.PanHasher;

/**
 * Payment Gateway REST API.
 *
 * All endpoints:
 *   - Require Idempotency-Key header
 *   - Log structured JSON (no PAN, no CVV in logs — ever)
 *   - Return typed error codes in response body
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Payment authorization and management")
public class PaymentController {

    private final PaymentGatewayPort gatewayPort;
    private final PaymentMapper mapper;
    private final PanHasher panHasher;

    @Operation(
        summary = "Authorize a payment",
        description = "Idempotent. Repeated requests with same Idempotency-Key return cached response."
    )
    @ApiResponse(responseCode = "201", description = "Payment authorized")
    @ApiResponse(responseCode = "402", description = "Payment declined (see declineReason)")
    @ApiResponse(responseCode = "409", description = "Idempotency key conflict")
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    @PostMapping("/authorize")
    public ResponseEntity<PaymentResponseDto> authorize(
        @RequestHeader("Idempotency-Key")
        @Parameter(description = "Unique request identifier (max 128 chars)", required = true)
        String idempotencyKey,

        @RequestHeader(value = "X-Merchant-Id", required = true)
        @Parameter(description = "Merchant UUID", required = true)
        String merchantId,

        @Valid @RequestBody AuthorizeRequestDto dto,
        HttpServletRequest httpRequest
    ) {
        // Hash PAN immediately — never passes further in plaintext
        String panHash = panHasher.hash(dto.pan());

        var request = mapper.toDomain(dto, idempotencyKey, merchantId, panHash);
        var response = gatewayPort.authorize(request);

        HttpStatus status = response.isApproved() ? HttpStatus.CREATED : HttpStatus.PAYMENT_REQUIRED;
        return ResponseEntity.status(status).body(mapper.toDto(response));
    }

    @Operation(summary = "Reverse a transaction")
    @ApiResponse(responseCode = "200", description = "Reversal submitted")
    @PostMapping("/{transactionId}/reverse")
    public ResponseEntity<PaymentResponseDto> reverse(
        @PathVariable String transactionId,

        @RequestHeader("Idempotency-Key")
        String idempotencyKey
    ) {
        var response = gatewayPort.reverse(transactionId, idempotencyKey);
        return ResponseEntity.ok(mapper.toDto(response));
    }
}
