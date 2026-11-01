package com.paypulse.provider.api;

import com.paypulse.domain.model.Money;

public sealed interface ProviderRefundResult
    permits ProviderRefundResult.Success,
            ProviderRefundResult.Declined,
            ProviderRefundResult.GatewayError {

    record Success(String providerRefundId, Money refundedAmount) implements ProviderRefundResult {}

    record Declined(String reason) implements ProviderRefundResult {}

    record GatewayError(String errorCode, String errorMessage, boolean isRetryable) implements ProviderRefundResult {}
}
