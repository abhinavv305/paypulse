package com.paypulse.outbox.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.paypulse.domain.event.PaymentDomainEvent;
import com.paypulse.outbox.model.OutboxEvent;
import com.paypulse.outbox.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionalOutboxPublisher {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public TransactionalOutboxPublisher(OutboxEventRepository repository) {
        this.repository = repository;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordEvent(PaymentDomainEvent event) {
        try {
            String payloadJson = objectMapper.writeValueAsString(event);
            OutboxEvent outboxEvent = new OutboxEvent(
                "PAYMENT",
                event.paymentId(),
                event.eventType(),
                payloadJson
            );
            repository.save(outboxEvent);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize outbox domain event: " + e.getMessage(), e);
        }
    }
}
