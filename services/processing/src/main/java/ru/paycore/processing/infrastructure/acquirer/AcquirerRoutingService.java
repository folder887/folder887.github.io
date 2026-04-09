package ru.paycore.processing.infrastructure.acquirer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.paycore.processing.infrastructure.kafka.dto.AuthorizeCommand;

import java.util.List;
import java.util.Optional;

/**
 * Smart acquirer routing — selects the best acquirer for each payment.
 *
 * Routing logic (priority order):
 *   1. If PAYCORE_PROCESSING_MODE=INTERNAL → always use InternalAcquirer (sandbox)
 *   2. If only one real acquirer is configured → use it
 *   3. If multiple acquirers are configured → route by rules:
 *      - Amount > 500 000 kopecks (5 000 RUB) → Tinkoff (higher limits)
 *      - MCC 5411, 5412 (food) → YooKassa (lower fees for retail)
 *      - Default → first available acquirer
 *   4. If primary fails → fallback to next acquirer (waterfall)
 *
 * All acquirer adapters are Spring beans implementing AcquirerPort.
 * Spring auto-discovers and injects all of them.
 */
@Slf4j
@Service
public class AcquirerRoutingService {

    private final List<AcquirerPort> acquirers;
    private final InternalAcquirer internalAcquirer;
    private final String processingMode;

    public AcquirerRoutingService(
        List<AcquirerPort> acquirers,
        InternalAcquirer internalAcquirer,
        @Value("${paycore.processing.mode:INTERNAL}") String processingMode
    ) {
        this.acquirers = acquirers;
        this.internalAcquirer = internalAcquirer;
        this.processingMode = processingMode;

        log.info("action=acquirer_routing_init mode={} acquirers={}",
            processingMode,
            acquirers.stream().map(AcquirerPort::name).toList());
    }

    /**
     * Authorize a payment through the best available acquirer.
     * Falls back to the next acquirer on error.
     */
    public AcquirerResult authorize(AcquirerRequest request) {
        if ("INTERNAL".equalsIgnoreCase(processingMode)) {
            return internalAcquirer.authorize(request);
        }

        var candidates = selectCandidates(request);

        for (var acquirer : candidates) {
            log.info("action=routing_attempt acquirer={} correlation_id={}",
                acquirer.name(), request.correlationId());

            var result = acquirer.authorize(request);

            if (result.success()) {
                return result;
            }

            // Only retry on infrastructure errors, not on business declines
            if (isBusinessDecline(result)) {
                log.info("action=routing_business_decline acquirer={} reason={}",
                    acquirer.name(), result.declineCode());
                return result;
            }

            log.warn("action=routing_acquirer_failed acquirer={} error={} trying_next={}",
                acquirer.name(), result.declineCode(), true);
        }

        return AcquirerResult.error("All acquirers failed", "none");
    }

    /** Build an ordered list of acquirers to try for this request. */
    private List<AcquirerPort> selectCandidates(AcquirerRequest request) {
        var realAcquirers = acquirers.stream()
            .filter(a -> !"internal".equals(a.name()))
            .filter(a -> a.supports(request))
            .toList();

        if (realAcquirers.isEmpty()) {
            log.warn("action=routing_no_acquirers fallback=internal");
            return List.of(internalAcquirer);
        }

        // Routing rules: put preferred acquirer first
        var preferred = selectPreferred(request, realAcquirers);
        if (preferred.isEmpty()) return realAcquirers;

        // preferred first, then others as fallback
        var result = new java.util.ArrayList<AcquirerPort>();
        result.add(preferred.get());
        realAcquirers.stream()
            .filter(a -> !a.name().equals(preferred.get().name()))
            .forEach(result::add);
        return result;
    }

    private Optional<AcquirerPort> selectPreferred(AcquirerRequest request, List<AcquirerPort> candidates) {
        // Large amounts → prefer Tinkoff (higher limits)
        if (request.amountKopecks() >= 500_000L) {
            return candidates.stream().filter(a -> "tinkoff".equals(a.name())).findFirst();
        }

        // Food MCC → prefer YooKassa (better retail rates)
        if ("5411".equals(request.mcc()) || "5412".equals(request.mcc())) {
            return candidates.stream().filter(a -> "yookassa".equals(a.name())).findFirst();
        }

        return Optional.empty();
    }

    /** Business declines should not be retried with another acquirer. */
    private boolean isBusinessDecline(AcquirerResult result) {
        if (result.declineCode() == null) return false;
        return switch (result.declineCode()) {
            case "insufficient_funds", "INSUFFICIENT_FUNDS",
                 "card_expired", "EXPIRED_CARD",
                 "fraud_suspected", "FRAUD",
                 "limit_exceeded", "LIMIT_EXCEEDED" -> true;
            default -> false;
        };
    }

    /** Build AcquirerRequest from AuthorizeCommand. */
    public static AcquirerRequest fromCommand(AuthorizeCommand command) {
        return new AcquirerRequest(
            command.correlationId(),
            command.merchantId(),
            command.amountKopecks(),
            command.currencyCode(),
            command.panFirstSix() != null ? command.panFirstSix() : "000000",
            command.mcc(),
            "PURCHASE",
            "Payment %s".formatted(command.correlationId())
        );
    }
}
