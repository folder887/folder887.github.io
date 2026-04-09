package ru.paycore.gateway.domain.model;

import java.util.Objects;

/**
 * Value object representing a monetary amount.
 *
 * INVARIANT: amount is always stored in kopecks (Long).
 * Float/Double arithmetic on monetary values is PROHIBITED.
 */
public record Money(long kopecks, CurrencyCode currency) {

    public Money {
        if (kopecks < 0) {
            throw new IllegalArgumentException("Amount cannot be negative: " + kopecks);
        }
        Objects.requireNonNull(currency, "Currency must not be null");
    }

    public static Money of(long kopecks, CurrencyCode currency) {
        return new Money(kopecks, currency);
    }

    public static Money ofRubles(long rubles, CurrencyCode currency) {
        return new Money(rubles * 100L, currency);
    }

    public Money add(Money other) {
        assertSameCurrency(other);
        return new Money(this.kopecks + other.kopecks, this.currency);
    }

    public Money subtract(Money other) {
        assertSameCurrency(other);
        if (other.kopecks > this.kopecks) {
            throw new IllegalArgumentException(
                "Cannot subtract %d from %d kopecks".formatted(other.kopecks, this.kopecks));
        }
        return new Money(this.kopecks - other.kopecks, this.currency);
    }

    public boolean isGreaterThan(Money other) {
        assertSameCurrency(other);
        return this.kopecks > other.kopecks;
    }

    public boolean isZero() {
        return this.kopecks == 0;
    }

    private void assertSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                "Currency mismatch: %s vs %s".formatted(this.currency, other.currency));
        }
    }

    @Override
    public String toString() {
        return "%d.%02d %s".formatted(kopecks / 100, kopecks % 100, currency.isoCode());
    }
}
