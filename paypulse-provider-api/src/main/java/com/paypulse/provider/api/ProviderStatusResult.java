package com.paypulse.provider.api;

import com.paypulse.domain.model.Money;
import com.paypulse.domain.model.PaymentStatus;
import java.util.Map;

public sealed interface ProviderStatusResult
    permits ProviderStatusResult.Found,
            ProviderStatusResult.NotFound,
            ProviderStatusResult.QueryError {

    record Found(
        String providerReference,
        PaymentStatus status,
        Money amount,
        Map<String, Object> rawDetails
    ) implements ProviderStatusResult {}

    record NotFound(String providerReference) implements ProviderStatusResult {}

    record QueryError(String errorCode, String errorMessage) implements ProviderStatusResult {}
}
