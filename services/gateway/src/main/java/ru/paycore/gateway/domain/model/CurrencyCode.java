package ru.paycore.gateway.domain.model;

/**
 * ISO 4217 numeric currency codes supported by PayCore.
 */
public enum CurrencyCode {
    RUB("643"),
    USD("840"),
    EUR("978"),
    CNY("156");

    private final String isoCode;

    CurrencyCode(String isoCode) {
        this.isoCode = isoCode;
    }

    public String isoCode() {
        return isoCode;
    }

    public static CurrencyCode fromIsoCode(String code) {
        for (CurrencyCode c : values()) {
            if (c.isoCode.equals(code)) {
                return c;
            }
        }
        throw new IllegalArgumentException("Unknown currency code: " + code);
    }
}
