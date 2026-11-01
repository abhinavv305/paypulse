package com.paypulse.web.controller;

import com.paypulse.provider.api.ProviderId;
import com.paypulse.webhook.service.WebhookIngestionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1/webhooks")
public class WebhookController {

    private final WebhookIngestionService ingestionService;

    public WebhookController(WebhookIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/{provider}")
    public ResponseEntity<Map<String, String>> handleWebhook(
            @PathVariable("provider") String providerStr,
            @RequestHeader Map<String, String> headers,
            @RequestBody byte[] rawPayload) {

        ProviderId providerId;
        try {
            providerId = ProviderId.fromString(providerStr);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("status", "REJECTED", "error", "Unknown provider: " + providerStr));
        }

        var result = ingestionService.ingestWebhook(providerId, headers, rawPayload);

        return switch (result.status()) {
            case ACCEPTED -> ResponseEntity.ok(Map.of("status", "ACKNOWLEDGED", "eventId", result.eventId() != null ? result.eventId() : ""));
            case DUPLICATE_ACKNOWLEDGED -> ResponseEntity.ok(Map.of("status", "DUPLICATE_ACKNOWLEDGED", "eventId", result.eventId() != null ? result.eventId() : ""));
            case INVALID_SIGNATURE -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "INVALID_SIGNATURE", "message", result.message()));
            case PROVIDER_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("status", "PROVIDER_NOT_FOUND", "message", result.message()));
        };
    }
}
