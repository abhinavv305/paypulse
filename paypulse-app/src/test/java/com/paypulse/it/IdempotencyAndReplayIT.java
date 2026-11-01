package com.paypulse.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class IdempotencyAndReplayIT {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Sequential replay with identical payload returns 200 OK and Idempotent-Replay header")
    void testSequentialReplayReturnsCachedResponse() throws Exception {
        String key = "idemp-" + UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        String payload = """
            {
              "amount": 2500,
              "currency": "USD",
              "provider": "MOCK",
              "captureMethod": "AUTOMATIC",
              "paymentMethod": { "type": "CARD", "token": "tok_123" }
            }
            """;

        // First request: 201 Created
        MockHttpServletResponse resp1 = mockMvc.perform(
            post("/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .header("X-Merchant-Id", merchantId.toString())
                .content(payload)
        ).andReturn().getResponse();

        assertThat(resp1.getStatus()).isEqualTo(201);
        JsonNode body1 = objectMapper.readTree(resp1.getContentAsString());
        String paymentId1 = body1.path("id").asText();

        // Second request with SAME key and SAME payload: 200 OK (Replay)
        MockHttpServletResponse resp2 = mockMvc.perform(
            post("/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .header("X-Merchant-Id", merchantId.toString())
                .content(payload)
        ).andReturn().getResponse();

        assertThat(resp2.getStatus()).isEqualTo(200);
        assertThat(resp2.getHeader("Idempotent-Replay")).isEqualTo("true");
        JsonNode body2 = objectMapper.readTree(resp2.getContentAsString());
        assertThat(body2.path("id").asText()).isEqualTo(paymentId1);
    }

    @Test
    @DisplayName("Reusing Idempotency-Key with altered payload returns 422 Unprocessable Entity")
    void testPayloadHashMismatchReturns422() throws Exception {
        String key = "mismatch-key-" + UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        String originalPayload = """
            {
              "amount": 2500,
              "currency": "USD",
              "provider": "MOCK"
            }
            """;

        MockHttpServletResponse resp1 = mockMvc.perform(
            post("/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .header("X-Merchant-Id", merchantId.toString())
                .content(originalPayload)
        ).andReturn().getResponse();

        assertThat(resp1.getStatus()).isEqualTo(201);

        // Same key, different amount (5000 instead of 2500)
        String alteredPayload = """
            {
              "amount": 5000,
              "currency": "USD",
              "provider": "MOCK"
            }
            """;

        MockHttpServletResponse resp2 = mockMvc.perform(
            post("/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .header("X-Merchant-Id", merchantId.toString())
                .content(alteredPayload)
        ).andReturn().getResponse();

        assertThat(resp2.getStatus()).isEqualTo(422);
        JsonNode errorBody = objectMapper.readTree(resp2.getContentAsString());
        assertThat(errorBody.path("errorCode").asText()).isEqualTo("IDEMPOTENCY_PAYLOAD_MISMATCH");
    }
}
