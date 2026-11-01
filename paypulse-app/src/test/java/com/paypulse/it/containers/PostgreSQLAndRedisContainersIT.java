package com.paypulse.it.containers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypulse.domain.model.Payment;
import com.paypulse.domain.model.PaymentStatus;
import com.paypulse.domain.repository.PaymentRepository;
import com.paypulse.idempotency.repository.IdempotencyKeyRepository;
import com.paypulse.provider.mock.MockPaymentAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Full Production Integration Test running directly against real PostgreSQL 16
 * and Redis 7 containers via Testcontainers.
 *
 * Enabled when DOCKER_AVAILABLE=true is present in the environment.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
public class PostgreSQLAndRedisContainersIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("paypulse")
        .withUsername("paypulse")
        .withPassword("paypulse");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (postgres.isRunning()) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
            registry.add("spring.flyway.enabled", () -> "true");
        }
        if (redis.isRunning()) {
            registry.add("spring.data.redis.host", redis::getHost);
            registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MockPaymentAdapter mockPaymentAdapter;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Verify end-to-end payment creation and idempotency against real PostgreSQL and Redis containers")
    void testEndToEndPaymentAgainstContainers() throws Exception {
        String idempotencyKey = "container-test-" + UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        String payload = """
            {
              "amount": 9900,
              "currency": "USD",
              "provider": "MOCK",
              "captureMethod": "AUTOMATIC",
              "paymentMethod": { "type": "CARD", "token": "tok_visa" },
              "description": "Testcontainers E2E payment"
            }
            """;

        MockHttpServletResponse response = mockMvc.perform(
            post("/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Merchant-Id", merchantId.toString())
                .content(payload)
        ).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(201);
        UUID paymentId = UUID.fromString(objectMapper.readTree(response.getContentAsString()).path("id").asText());

        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getAmountMinorUnits()).isEqualTo(9900L);

        // Verify idempotency record saved in real Postgres
        assertThat(idempotencyKeyRepository.findByMerchantIdAndKey(merchantId, idempotencyKey)).isPresent();
    }
}
