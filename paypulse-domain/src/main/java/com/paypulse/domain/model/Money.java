package com.paypulse.domain.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.paypulse.domain.exception.InvalidPaymentAmountException;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Currency;
import java.util.Objects;

@Embeddable
public final class Money implements Serializable, Comparable<Money> {

    @Column(name = "amount_minor_units", nullable = false)
    private final long amountMinorUnits;

    @Column(name = "currency", nullable = false, length = 3)
    private final String currency;

    protected Money() {
        this.amountMinorUnits = 0;
        this.currency = "USD";
    }

    @JsonCreator
    public Money(
            @JsonProperty("amountMinorUnits") long amountMinorUnits,
            @JsonProperty("currency") String currency) {
        if (amountMinorUnits < 0) {
            throw new InvalidPaymentAmountException("Amount cannot be negative: " + amountMinorUnits);
        }
        Objects.requireNonNull(currency, "Currency must not be null");
        this.currency = currency.toUpperCase().trim();
        validateCurrency(this.currency);
        this.amountMinorUnits = amountMinorUnits;
    }

    public static Money of(long amountMinorUnits, String currency) {
        return new Money(amountMinorUnits, currency);
    }

    public static Money ofMinorUnits(long minorUnits, String currency) {
        return new Money(minorUnits, currency);
    }

    private static void validateCurrency(String currencyCode) {
        try {
            Currency.getInstance(currencyCode);
        } catch (IllegalArgumentException e) {
            throw new InvalidPaymentAmountException("Invalid ISO 4217 currency code: " + currencyCode);
        }
    }

    public long getAmountMinorUnits() {
        return amountMinorUnits;
    }

    public String getCurrency() {
        return currency;
    }

    public Money plus(Money other) {
        ensureSameCurrency(other);
        return new Money(Math.addExact(this.amountMinorUnits, other.amountMinorUnits), this.currency);
    }

    public Money minus(Money other) {
        ensureSameCurrency(other);
        if (this.amountMinorUnits < other.amountMinorUnits) {
            throw new InvalidPaymentAmountException("Subtraction would result in negative money: " 
                    + this.amountMinorUnits + " - " + other.amountMinorUnits);
        }
        return new Money(this.amountMinorUnits - other.amountMinorUnits, this.currency);
    }

    public boolean isPositive() {
        return amountMinorUnits > 0;
    }

    public boolean isZero() {
        return amountMinorUnits == 0;
    }

    private void ensureSameCurrency(Money other) {
        Objects.requireNonNull(other, "Other money must not be null");
        if (!this.currency.equalsIgnoreCase(other.currency)) {
            throw new InvalidPaymentAmountException("Currency mismatch: " + this.currency + " vs " + other.currency);
        }
    }

    @Override
    public int compareTo(Money other) {
        ensureSameCurrency(other);
        return Long.compare(this.amountMinorUnits, other.amountMinorUnits);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Money money = (Money) o;
        return amountMinorUnits == money.amountMinorUnits && Objects.equals(currency, money.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amountMinorUnits, currency);
    }

    @Override
    public String toString() {
        return currency + " " + (amountMinorUnits / 100.0);
    }
}
