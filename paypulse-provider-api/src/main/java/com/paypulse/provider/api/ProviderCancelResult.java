package com.paypulse.provider.api;

public sealed interface ProviderCancelResult
    permits ProviderCancelResult.Success,
            ProviderCancelResult.Failed {

    record Success(String providerReference) implements ProviderCancelResult {}

    record Failed(String providerReference, String errorMessage) implements ProviderCancelResult {}
}
