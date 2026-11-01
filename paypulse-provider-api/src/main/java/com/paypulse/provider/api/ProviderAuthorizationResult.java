package com.paypulse.provider.api;

import com.paypulse.domain.model.Money;
import java.util.Map;

public sealed interface ProviderAuthorizationResult 
    permits ProviderAuthorizationResult.Success, 
            ProviderAuthorizationResult.ActionRequired, 
            ProviderAuthorizationResult.Declined, 
            ProviderAuthorizationResult.GatewayError {

    record Success(
        String providerReference,
        String providerAuthCode,
        Money authorizedAmount,
        Map<String, Object> rawResponse
    ) implements ProviderAuthorizationResult {}

    record ActionRequired(
        String providerReference,
        String redirectUrl,
        String clientSecret,
        Map<String, Object> rawResponse
    ) implements ProviderAuthorizationResult {}

    record Declined(
        String providerReference,
        DeclineCode declineCode,
        String declineMessage,
        Map<String, Object> rawResponse
    ) implements ProviderAuthorizationResult {}

    record GatewayError(
        String errorCode,
        String errorMessage,
        boolean isRetryable,
        Throwable cause
    ) implements ProviderAuthorizationResult {}
}
