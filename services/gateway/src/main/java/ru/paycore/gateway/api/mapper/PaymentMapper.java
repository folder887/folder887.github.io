package ru.paycore.gateway.api.mapper;

import org.springframework.stereotype.Component;
import ru.paycore.gateway.api.dto.AuthorizeRequestDto;
import ru.paycore.gateway.api.dto.PaymentResponseDto;
import ru.paycore.gateway.domain.model.*;

import java.time.Instant;
import java.util.UUID;

@Component
public class PaymentMapper {

    public PaymentRequest toDomain(
            AuthorizeRequestDto dto,
            String idempotencyKey,
            String merchantId,
            String panHash
    ) {
        String pan = dto.pan();
        String panLastFour = pan.substring(pan.length() - 4);
        String panFirstSix = pan.substring(0, Math.min(6, pan.length()));

        CurrencyCode currency = CurrencyCode.fromIsoCode(dto.currencyCode());
        Money amount = Money.of(dto.amountKopecks(), currency);

        TransactionType txType;
        try {
            txType = TransactionType.valueOf(dto.transactionType().toUpperCase());
        } catch (IllegalArgumentException e) {
            txType = TransactionType.PURCHASE;
        }

        return new PaymentRequest(
                UUID.randomUUID(),
                idempotencyKey,
                UUID.fromString(merchantId),
                panHash,
                panLastFour,
                panFirstSix,
                dto.expiryMonth(),
                dto.expiryYear(),
                detectNetwork(panFirstSix),
                amount,
                dto.mcc(),
                txType,
                dto.metadata(),
                Instant.now()
        );
    }

    public PaymentResponseDto toDto(PaymentResponse response) {
        return new PaymentResponseDto(
                response.transactionId(),
                response.idempotencyKey(),
                response.status().name(),
                response.amount().kopecks(),
                response.amount().currency().isoCode(),
                response.authCode(),
                response.rrn(),
                response.declineReason(),
                response.processedAt()
        );
    }

    private String detectNetwork(String firstSix) {
        if (firstSix.startsWith("4")) return "VISA";
        if (firstSix.startsWith("5") || firstSix.startsWith("2")) return "MASTERCARD";
        if (firstSix.startsWith("220")) return "MIR";
        return "UNKNOWN";
    }
}
