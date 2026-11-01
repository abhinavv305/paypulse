package com.paypulse.provider.api;

public enum ProviderId {
    STRIPE,
    ADYEN,
    MOCK;

    public static ProviderId fromString(String value) {
        if (value == null) {
            return MOCK;
        }
        try {
            return ProviderId.valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown payment provider: " + value);
        }
    }
}
