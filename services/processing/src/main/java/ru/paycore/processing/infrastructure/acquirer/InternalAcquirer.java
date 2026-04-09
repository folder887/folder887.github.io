package ru.paycore.processing.infrastructure.acquirer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.paycore.processing.infrastructure.persistence.repository.AccountRepository;

import java.util.UUID;

/**
 * Internal (sandbox) acquirer — uses our own ledger.
 *
 * Active when no real acquirers are configured (PAYCORE_PROCESSING_MODE=INTERNAL).
 * Useful for local development and integration tests — no external dependencies.
 *
 * Does NOT require a payment license.
 * Does NOT move real money.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InternalAcquirer implements AcquirerPort {

    private final AccountRepository accountRepository;

    @Override
    public String name() {
        return "internal";
    }

    @Override
    public AcquirerResult authorize(AcquirerRequest request) {
        var account = accountRepository.findByMerchantIdForUpdate(request.merchantId())
            .orElse(null);

        if (account == null) {
            log.warn("action=internal_no_account merchant_id={}", request.merchantId());
            return AcquirerResult.declined("NO_ACCOUNT", "Merchant account not found", name());
        }

        if (account.getBalance() < request.amountKopecks()) {
            return AcquirerResult.declined("INSUFFICIENT_FUNDS", "Balance too low", name());
        }

        int updated = accountRepository.reserveBalance(
            account.getId(),
            request.amountKopecks(),
            account.getVersion()
        );

        if (updated == 0) {
            return AcquirerResult.error("Concurrent modification on account", name());
        }

        var externalId = UUID.randomUUID().toString();
        var authCode = "%06d".formatted((int)(Math.random() * 999999));

        log.info("action=internal_authorized correlation_id={} auth_code={}",
            request.correlationId(), authCode);

        return AcquirerResult.authorized(externalId, authCode, name());
    }
}
