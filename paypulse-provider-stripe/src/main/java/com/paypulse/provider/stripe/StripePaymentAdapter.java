package com.paypulse.provider.stripe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypulse.domain.model.Money;
import com.paypulse.domain.model.PaymentStatus;
import com.paypulse.provider.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Component
public class StripePaymentAdapter implements PaymentProviderGateway {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentAdapter.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String webhookSecret;

    public StripePaymentAdapter(
            @Value("${paypulse.providers.stripe.base-url:https://api.stripe.com}") String baseUrl,
            @Value("${paypulse.providers.stripe.api-key:sk_test_placeholder}") String apiKey,
            @Value("${paypulse.providers.stripe.webhook-secret:whsec_placeholder}") String webhookSecret) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.webhookSecret = webhookSecret;
    }

    @Override
    public ProviderId getProviderId() {
        return ProviderId.STRIPE;
    }

    @Override
    public ProviderAuthorizationResult authorize(AuthorizationRequest request) {
        String url = baseUrl + "/v1/charges";
        HttpHeaders headers = buildHeaders(request.idempotencyKey());

        Map<String, Object> body = new HashMap<>();
        body.put("amount", request.money().getAmountMinorUnits());
        body.put("currency", request.money().getCurrency().toLowerCase());
        body.put("capture", request.captureMethod() == com.paypulse.domain.model.CaptureMethod.AUTOMATIC);
        if (request.paymentMethodToken() != null) {
            body.put("source", request.paymentMethodToken());
        }
        if (request.customerEmail() != null) {
            body.put("receipt_email", request.customerEmail());
        }

        try {
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());

            String chargeId = root.path("id").asText("ch_unknown");
            String status = root.path("status").asText();

            if ("succeeded".equalsIgnoreCase(status) || "pending".equalsIgnoreCase(status)) {
                return new ProviderAuthorizationResult.Success(
                    chargeId,
                    root.path("authorization_code").asText("AUTH_" + UUID.randomUUID().toString().substring(0, 8)),
                    request.money(),
                    Map.of("raw", root)
                );
            } else {
                return new ProviderAuthorizationResult.Declined(
                    chargeId,
                    DeclineCode.GENERIC_DECLINE,
                    "Payment status: " + status,
                    Map.of("raw", root)
                );
            }
        } catch (HttpStatusCodeException ex) {
            return handleStripeHttpError(ex);
        } catch (ResourceAccessException ex) {
            log.error("Network timeout calling Stripe: {}", ex.getMessage());
            return new ProviderAuthorizationResult.GatewayError(
                "NETWORK_TIMEOUT",
                "Connection or read timeout calling Stripe API: " + ex.getMessage(),
                true,
                ex
            );
        } catch (Exception ex) {
            log.error("Unexpected error calling Stripe: {}", ex.getMessage(), ex);
            return new ProviderAuthorizationResult.GatewayError(
                "INTERNAL_GATEWAY_ERROR",
                ex.getMessage(),
                false,
                ex
            );
        }
    }

    @Override
    public ProviderCaptureResult capture(CaptureRequest request) {
        String url = baseUrl + "/v1/charges/" + request.providerPaymentId() + "/capture";
        HttpHeaders headers = buildHeaders(request.idempotencyKey());

        Map<String, Object> body = Map.of(
            "amount", request.money().getAmountMinorUnits()
        );

        try {
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());

            return new ProviderCaptureResult.Success(
                root.path("id").asText(request.providerPaymentId()),
                request.money(),
                Map.of("raw", root)
            );
        } catch (HttpStatusCodeException ex) {
            return new ProviderCaptureResult.Declined(
                request.providerPaymentId(),
                DeclineCode.GENERIC_DECLINE,
                ex.getResponseBodyAsString()
            );
        } catch (Exception ex) {
            return new ProviderCaptureResult.GatewayError("CAPTURE_ERROR", ex.getMessage(), true);
        }
    }

    @Override
    public ProviderCancelResult cancel(CancelRequest request) {
        return new ProviderCancelResult.Success(request.providerPaymentId());
    }

    @Override
    public ProviderRefundResult refund(RefundRequest request) {
        String url = baseUrl + "/v1/refunds";
        HttpHeaders headers = buildHeaders(request.idempotencyKey());

        Map<String, Object> body = Map.of(
            "charge", request.providerPaymentId(),
            "amount", request.money().getAmountMinorUnits()
        );

        try {
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());

            return new ProviderRefundResult.Success(
                root.path("id").asText("re_" + UUID.randomUUID()),
                request.money()
            );
        } catch (Exception ex) {
            return new ProviderRefundResult.GatewayError("REFUND_ERROR", ex.getMessage(), false);
        }
    }

    @Override
    public ProviderStatusResult queryStatus(ProviderQueryRequest request) {
        String url = baseUrl + "/v1/charges/" + request.providerPaymentId();
        HttpHeaders headers = buildHeaders(null);

        try {
            HttpEntity<?> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());

            String statusStr = root.path("status").asText();
            PaymentStatus domainStatus = "succeeded".equalsIgnoreCase(statusStr) 
                ? PaymentStatus.SUCCEEDED 
                : PaymentStatus.AUTHORIZED;

            long amount = root.path("amount").asLong(0L);
            String curr = root.path("currency").asText("USD").toUpperCase();

            return new ProviderStatusResult.Found(
                root.path("id").asText(request.providerPaymentId()),
                domainStatus,
                Money.of(amount, curr),
                Map.of("stripeStatus", statusStr)
            );
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                return new ProviderStatusResult.NotFound(request.providerPaymentId());
            }
            return new ProviderStatusResult.QueryError("HTTP_" + ex.getStatusCode().value(), ex.getMessage());
        } catch (Exception ex) {
            return new ProviderStatusResult.QueryError("UNKNOWN", ex.getMessage());
        }
    }

    @Override
    public WebhookVerificationResult verifyWebhookSignature(Map<String, String> headers, byte[] rawPayload) {
        String sigHeader = headers.entrySet().stream()
            .filter(e -> "Stripe-Signature".equalsIgnoreCase(e.getKey()))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);

        if (sigHeader == null || sigHeader.isBlank()) {
            return new WebhookVerificationResult.Invalid("Missing Stripe-Signature header");
        }

        try {
            Map<String, String> sigParts = parseSigHeader(sigHeader);
            String timestamp = sigParts.get("t");
            String signature = sigParts.get("v1");

            if (timestamp == null || signature == null) {
                return new WebhookVerificationResult.Invalid("Invalid Stripe-Signature header format");
            }

            long eventTime = Long.parseLong(timestamp);
            long currentTime = Instant.now().getEpochSecond();
            if (Math.abs(currentTime - eventTime) > 300) {
                return new WebhookVerificationResult.Invalid("Webhook timestamp outside acceptable 300s window");
            }

            String signedPayload = timestamp + "." + new String(rawPayload, StandardCharsets.UTF_8);
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expectedHash = hmac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
            byte[] actualHash = HexFormat.of().parseHex(signature);

            if (MessageDigest.isEqual(expectedHash, actualHash)) {
                return new WebhookVerificationResult.Valid("evt_" + UUID.randomUUID(), "stripe.event");
            } else {
                return new WebhookVerificationResult.Invalid("HMAC signature mismatch");
            }
        } catch (Exception ex) {
            return new WebhookVerificationResult.Invalid("Signature verification exception: " + ex.getMessage());
        }
    }

    @Override
    public NormalizedWebhookEvent parseWebhookPayload(byte[] rawPayload) {
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            String eventId = root.path("id").asText("evt_unknown");
            String type = root.path("type").asText("unknown");
            JsonNode dataObject = root.path("data").path("object");
            String chargeId = dataObject.path("id").asText();
            long amount = dataObject.path("amount").asLong(0L);
            String currency = dataObject.path("currency").asText("USD").toUpperCase();

            PaymentStatus targetStatus = switch (type) {
                case "charge.succeeded", "payment_intent.succeeded" -> PaymentStatus.SUCCEEDED;
                case "charge.failed", "payment_intent.payment_failed" -> PaymentStatus.DECLINED;
                case "charge.refunded" -> PaymentStatus.REFUNDED;
                default -> PaymentStatus.AUTHORIZED;
            };

            return new NormalizedWebhookEvent(
                ProviderId.STRIPE,
                eventId,
                type,
                null,
                chargeId,
                targetStatus,
                amount > 0 ? Money.of(amount, currency) : null,
                Instant.now(),
                new String(rawPayload, StandardCharsets.UTF_8)
            );
        } catch (Exception ex) {
            throw new RuntimeException("Failed to parse Stripe webhook JSON: " + ex.getMessage(), ex);
        }
    }

    private HttpHeaders buildHeaders(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    private Map<String, String> parseSigHeader(String sigHeader) {
        Map<String, String> map = new HashMap<>();
        String[] pairs = sigHeader.split(",");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(kv[0].trim(), kv[1].trim());
            }
        }
        return map;
    }

    private ProviderAuthorizationResult handleStripeHttpError(HttpStatusCodeException ex) {
        try {
            JsonNode errorNode = objectMapper.readTree(ex.getResponseBodyAsString()).path("error");
            String code = errorNode.path("code").asText();
            String message = errorNode.path("message").asText("Payment declined");

            DeclineCode declineCode = switch (code) {
                case "card_declined" -> DeclineCode.INSUFFICIENT_FUNDS;
                case "expired_card" -> DeclineCode.CARD_EXPIRED;
                case "incorrect_cvc", "invalid_cvc" -> DeclineCode.INVALID_CREDENTIALS;
                case "rate_limit" -> DeclineCode.PROVIDER_THROTTLED;
                default -> DeclineCode.GENERIC_DECLINE;
            };

            return new ProviderAuthorizationResult.Declined(
                "ch_declined",
                declineCode,
                message,
                Map.of("rawError", errorNode)
            );
        } catch (Exception parseEx) {
            return new ProviderAuthorizationResult.GatewayError(
                "HTTP_" + ex.getStatusCode().value(),
                ex.getMessage(),
                ex.getStatusCode().is5xxServerError(),
                ex
            );
        }
    }
}
