package com.paypulse.it;

import com.paypulse.domain.model.CaptureMethod;
import com.paypulse.domain.model.Money;
import com.paypulse.domain.model.Payment;
import com.paypulse.domain.model.PaymentStatus;
import com.paypulse.domain.repository.PaymentRepository;
import com.paypulse.webhook.model.WebhookEvent;
import com.paypulse.webhook.repository.WebhookEventRepository;
import com.paypulse.webhook.service.WebhookProcessingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class WebhookDeduplicationAndOrderingIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebhookEventRepository webhookRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private WebhookProcessingService webhookProcessingService;

    @Test
    @DisplayName("Verify duplicate concurrent webhooks are deduplicated and acknowledged immediately with 200 OK")
    void testWebhookDeduplication() throws Exception {
        int threads = 10;
        String eventId = "evt_dedup_" + UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        List<MockHttpServletResponse> responses = Collections.synchronizedList(new ArrayList<>());

        String payload = String.format("{\"id\": \"%s\", \"type\": \"payment.succeeded\"}", eventId);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    MockHttpServletResponse response = mockMvc.perform(
                        post("/v1/webhooks/mock")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Mock-Signature", "sig_" + eventId)
                            .content(payload)
                    ).andReturn().getResponse();

                    responses.add(response);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // All 10 requests must receive HTTP 200 OK
        assertThat(responses).hasSize(threads);
        for (MockHttpServletResponse resp : responses) {
            assertThat(resp.getStatus()).isEqualTo(200);
        }

        // Database must contain exactly 1 entry for this provider and event ID
        List<WebhookEvent> events = webhookRepository.findAll().stream()
            .filter(e -> eventId.equals(e.getProviderEventId()))
            .toList();
        assertThat(events).hasSize(1);
    }

    @Test
    @DisplayName("Verify out-of-order webhook does not regress a SUCCEEDED payment")
    void testOutOfOrderWebhookDoesNotRegressState() {
        // Setup payment already in SUCCEEDED state
        Payment payment = new Payment(
            UUID.randomUUID(),
            null,
            "MOCK",
            Money.of(5000, "USD"),
            CaptureMethod.AUTOMATIC,
            "out-of-order@test.com",
            "Test Payment"
        );
        payment.markAuthorizing();
        payment.markSucceeded("mock_ref_123");
        payment = paymentRepository.save(payment);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        // Stale webhook arrives attempting to transition to AUTHORIZING or CREATED
        WebhookEvent staleEvent = new WebhookEvent(
            "MOCK",
            "evt_stale_" + UUID.randomUUID(),
            "payment.created",
            "X-Mock-Signature: sig_stale",
            "{\"status\": \"created\", \"providerReference\": \"mock_ref_123\"}"
        );
        staleEvent = webhookRepository.save(staleEvent);

        // Process event
        boolean processed = webhookProcessingService.processSingleEvent(staleEvent);
        assertThat(processed).isTrue();

        // Webhook event is marked SUPERSEDED
        WebhookEvent reloadedEvent = webhookRepository.findById(staleEvent.getId()).orElseThrow();
        assertThat(reloadedEvent.getStatus()).isEqualTo("SUPERSEDED");

        // Payment status remains SUCCEEDED (not regressed!)
        Payment reloadedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reloadedPayment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }
}
