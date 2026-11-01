package com.paypulse.provider.api;

import com.paypulse.domain.model.Money;
import java.util.Map;

public sealed interface ProviderCaptureResult 
    permits ProviderCaptureResult.Success, 
            ProviderCaptureResult.Declined, 
            ProviderCaptureResult.GatewayError {

    record Success(
        String providerReference,
        Money capturedAmount,
        Map<String, Object> rawResponse
    ) implements ProviderCaptureResult {}

    record Declined(
        String providerReference,
        DeclineCode declineCode,
        String message
    ) implements ProviderCaptureResult {}

    record GatewayError(
        String errorCode,
        String errorMessage,
        boolean isRetryable
    ) implements ProviderCaptureResult {}
}
