package ru.paycore.gateway.domain.exception;

public final class MerchantNotFoundException extends PayCoreException {
    public MerchantNotFoundException(String merchantId) {
        super(ErrorCode.MERCHANT_NOT_FOUND, "Merchant not found: " + merchantId);
    }
}
