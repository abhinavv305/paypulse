package com.paypulse.provider.adyen;

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
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Component
public class AdyenPaymentAdapter implements PaymentProviderGateway {

    private static final Logger log = LoggerFactory.getLogger(AdyenPaymentAdapter.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String merchantAccount;
    private final String hmacKey;

    public AdyenPaymentAdapter(
            @Value("${paypulse.providers.adyen.base-url:https://checkout-test.adyen.com/v70}") String baseUrl,
            @Value("${paypulse.providers.adyen.api-key:adyen_key_placeholder}") String apiKey,
            @Value("${paypulse.providers.adyen.merchant-account:TestMerchant}") String merchantAccount,
            @Value("${paypulse.providers.adyen.hmac-key:44782def4f...}") String hmacKey) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.merchantAccount = merchantAccount;
        this.hmacKey = hmacKey;
    }

    @Override
    public ProviderId getProviderId() {
        return ProviderId.ADYEN;
    }

    @Override
    public ProviderAuthorizationResult authorize(AuthorizationRequest request) {
        String url = baseUrl + "/payments";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (request.idempotencyKey() != null) {
            headers.set("Idempotency-Key", request.idempotencyKey());
        }

        Map<String, Object> amountMap = Map.of(
            "currency", request.money().getCurrency(),
            "value", request.money().getAmountMinorUnits()
        );

        Map<String, Object> body = new HashMap<>();
        body.put("merchantAccount", merchantAccount);
        body.put("amount", amountMap);
        body.put("reference", request.paymentId().toString());
        body.put("returnUrl", "https://paypulse.io/adyen/return");

        try {
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());

            String pspReference = root.path("pspReference").asText("psp_" + UUID.randomUUID());
            String resultCode = root.path("resultCode").asText();

            if ("Authorised".equalsIgnoreCase(resultCode)) {
                return new ProviderAuthorizationResult.Success(
                    pspReference,
                    root.path("authCode").asText("AUTH_" + UUID.randomUUID().toString().substring(0, 8)),
                    request.money(),
                    Map.of("raw", root)
                );
            } else if ("IdentifyShopper".equalsIgnoreCase(resultCode) || "ChallengeShopper".equalsIgnoreCase(resultCode)) {
                return new ProviderAuthorizationResult.ActionRequired(
                    pspReference,
                    root.path("action").path("url").asText(),
                    null,
                    Map.of("raw", root)
                );
            } else {
                return new ProviderAuthorizationResult.Declined(
                    pspReference,
                    DeclineCode.DO_NOT_HONOR,
                    "Adyen resultCode: " + resultCode,
                    Map.of("raw", root)
                );
            }
        } catch (HttpStatusCodeException ex) {
            return new ProviderAuthorizationResult.GatewayError("HTTP_" + ex.getStatusCode().value(), ex.getMessage(), true, ex);
        } catch (Exception ex) {
            return new ProviderAuthorizationResult.GatewayError("INTERNAL_ERROR", ex.getMessage(), false, ex);
        }
    }

    @Override
    public ProviderCaptureResult capture(CaptureRequest request) {
        String url = baseUrl + "/payments/" + request.providerPaymentId() + "/captures";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
            "merchantAccount", merchantAccount,
            "amount", Map.of("currency", request.money().getCurrency(), "value", request.money().getAmountMinorUnits())
        );

        try {
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());

            return new ProviderCaptureResult.Success(
                root.path("pspReference").asText(request.providerPaymentId()),
                request.money(),
                Map.of("raw", root)
            );
        } catch (Exception ex) {
            return new ProviderCaptureResult.GatewayError("ADYEN_CAPTURE_ERROR", ex.getMessage(), true);
        }
    }

    @Override
    public ProviderCancelResult cancel(CancelRequest request) {
        return new ProviderCancelResult.Success(request.providerPaymentId());
    }

    @Override
    public ProviderRefundResult refund(RefundRequest request) {
        String url = baseUrl + "/payments/" + request.providerPaymentId() + "/refunds";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
            "merchantAccount", merchantAccount,
            "amount", Map.of("currency", request.money().getCurrency(), "value", request.money().getAmountMinorUnits())
        );

        try {
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());

            return new ProviderRefundResult.Success(
                root.path("pspReference").asText("psp_ref_" + UUID.randomUUID()),
                request.money()
            );
        } catch (Exception ex) {
            return new ProviderRefundResult.GatewayError("ADYEN_REFUND_ERROR", ex.getMessage(), false);
        }
    }

    @Override
    public ProviderStatusResult queryStatus(ProviderQueryRequest request) {
        return new ProviderStatusResult.NotFound(request.providerPaymentId());
    }

    @Override
    public WebhookVerificationResult verifyWebhookSignature(Map<String, String> headers, byte[] rawPayload) {
        String adyenHmac = headers.get("X-Adyen-Hmac");
        if (adyenHmac == null || adyenHmac.isBlank()) {
            return new WebhookVerificationResult.Invalid("Missing X-Adyen-Hmac header");
        }

        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(hmacKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = hmac.doFinal(rawPayload);
            byte[] actual = Base64.getDecoder().decode(adyenHmac);

            if (MessageDigest.isEqual(expected, actual)) {
                return new WebhookVerificationResult.Valid("adyen_evt_" + UUID.randomUUID(), "AUTHORISATION");
            }
            return new WebhookVerificationResult.Invalid("Adyen HMAC signature mismatch");
        } catch (Exception ex) {
            return new WebhookVerificationResult.Invalid("Adyen signature verification exception: " + ex.getMessage());
        }
    }

    @Override
    public NormalizedWebhookEvent parseWebhookPayload(byte[] rawPayload) {
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            JsonNode item = root.path("notificationItems").get(0).path("NotificationRequestItem");
            String pspRef = item.path("pspReference").asText();
            String eventCode = item.path("eventCode").asText();
            boolean success = item.path("success").asBoolean(false);

            PaymentStatus targetStatus = "AUTHORISATION".equalsIgnoreCase(eventCode) && success 
                ? PaymentStatus.AUTHORIZED 
                : PaymentStatus.DECLINED;

            long amount = item.path("amount").path("value").asLong(0L);
            String curr = item.path("amount").path("currency").asText("USD");

            return new NormalizedWebhookEvent(
                ProviderId.ADYEN,
                item.path("eventCode").asText() + "_" + pspRef,
                eventCode,
                null,
                pspRef,
                targetStatus,
                Money.of(amount, curr),
                Instant.now(),
                new String(rawPayload, StandardCharsets.UTF_8)
            );
        } catch (Exception ex) {
            throw new RuntimeException("Failed to parse Adyen notification JSON: " + ex.getMessage(), ex);
        }
    }
}
