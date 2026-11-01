package com.paypulse.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypulse.domain.model.Payment;
import com.paypulse.domain.model.PaymentStatus;
import com.paypulse.domain.repository.PaymentRepository;
import com.paypulse.idempotency.model.IdempotencyKey;
import com.paypulse.idempotency.repository.IdempotencyKeyRepository;
import com.paypulse.provider.mock.MockPaymentAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class ConcurrentPaymentStampedeIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MockPaymentAdapter mockPaymentAdapter;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockPaymentAdapter.resetCounts();
    }

    @Test
    @DisplayName("Verify that 100 concurrent identical payment requests yield exactly 1 logical payment and 1 provider-side operation")
    void test100ConcurrentIdenticalPaymentRequests() throws Exception {
        int totalRequests = 100;
        String sharedIdempotencyKey = "stampede-" + UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        String payload = """
            {
              "amount": 7500,
              "currency": "USD",
              "provider": "MOCK",
              "captureMethod": "AUTOMATIC",
              "paymentMethod": {
                "type": "CARD",
                "token": "tok_visa_4242"
              },
              "customer": {
                "email": "stampede.user@paypulse.io",
                "name": "Alex Pulse"
              },
              "description": "Load Test 100 Concurrent Requests"
            }
            """;

        ExecutorService executor = Executors.newFixedThreadPool(totalRequests);
        CyclicBarrier startBarrier = new CyclicBarrier(totalRequests);
        CountDownLatch doneLatch = new CountDownLatch(totalRequests);

        List<MockHttpServletResponse> responses = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger networkErrors = new AtomicInteger(0);

        // Fire 100 concurrent threads simultaneously
        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    startBarrier.await(); // Synchronize all 100 threads to release simultaneously
                    MockHttpServletResponse response = mockMvc.perform(
                        post("/v1/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", sharedIdempotencyKey)
                            .header("X-Merchant-Id", merchantId.toString())
                            .content(payload)
                    ).andReturn().getResponse();

                    responses.add(response);
                } catch (Exception e) {
                    networkErrors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        assertThat(completed).as("All 100 concurrent requests should complete within 30 seconds").isTrue();
        executor.shutdown();

        assertThat(networkErrors.get()).as("Zero execution/dispatch errors expected").isEqualTo(0);
        assertThat(responses).hasSize(totalRequests);

        // Invariant 1: Exactly ONE provider-side charge operation executed!
        assertThat(mockPaymentAdapter.getChargeCallCount())
            .as("Underlying payment gateway must be invoked exactly ONCE")
            .isEqualTo(1);

        // Invariant 2: Exactly ONE idempotency record in database
        Optional<IdempotencyKey> idempRecord = idempotencyKeyRepository.findByMerchantIdAndKey(merchantId, sharedIdempotencyKey);
        assertThat(idempRecord).isPresent();
        assertThat(idempRecord.get().getStatus()).isEqualTo("COMPLETED");
        UUID paymentId = idempRecord.get().getResourceId();
        assertThat(paymentId).isNotNull();

        // Invariant 3: Exactly ONE payment record in database with status SUCCEEDED
        Optional<Payment> paymentOpt = paymentRepository.findById(paymentId);
        assertThat(paymentOpt).isPresent();
        Payment payment = paymentOpt.get();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getAmountMinorUnits()).isEqualTo(7500L);
        assertThat(payment.getCurrency()).isEqualTo("USD");

        // Invariant 4: Analyze HTTP response status codes across all 100 requests
        int count201Created = 0;
        int count200Replay = 0;
        int count409Conflict = 0;
        int countOther = 0;

        for (MockHttpServletResponse resp : responses) {
            int status = resp.getStatus();
            if (status == 201) {
                count201Created++;
                JsonNode body = objectMapper.readTree(resp.getContentAsString());
                assertThat(body.path("id").asText()).isEqualTo(paymentId.toString());
                assertThat(body.path("status").asText()).isEqualTo("SUCCEEDED");
            } else if (status == 200) {
                count200Replay++;
                assertThat(resp.getHeader("Idempotent-Replay")).isEqualTo("true");
                JsonNode body = objectMapper.readTree(resp.getContentAsString());
                assertThat(body.path("id").asText()).isEqualTo(paymentId.toString());
            } else if (status == 409) {
                count409Conflict++;
                assertThat(resp.getHeader("Retry-After")).isNotNull();
            } else {
                countOther++;
            }
        }

        // Exactly one request was the winner that created the payment
        assertThat(count201Created).as("Exactly one request must win and return 201 Created").isEqualTo(1);
        assertThat(countOther).as("Zero unhandled exceptions or 500 errors").isEqualTo(0);

        // All 99 contenders received either a handled 409 Conflict (with Retry-After) or 200 Replay
        assertThat(count201Created + count200Replay + count409Conflict)
            .as("Total responses must account for all 100 requests")
            .isEqualTo(totalRequests);
    }
}
