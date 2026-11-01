package com.paypulse.provider.api;

import java.util.Map;

public interface PaymentProviderGateway {

    ProviderId getProviderId();

    ProviderAuthorizationResult authorize(AuthorizationRequest request);

    ProviderCaptureResult capture(CaptureRequest request);

    ProviderCancelResult cancel(CancelRequest request);

    ProviderRefundResult refund(RefundRequest request);

    ProviderStatusResult queryStatus(ProviderQueryRequest request);

    WebhookVerificationResult verifyWebhookSignature(Map<String, String> headers, byte[] rawPayload);

    NormalizedWebhookEvent parseWebhookPayload(byte[] rawPayload);
}
