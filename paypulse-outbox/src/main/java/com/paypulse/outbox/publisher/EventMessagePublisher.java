package com.paypulse.outbox.publisher;

public interface EventMessagePublisher {
    void publish(String eventType, String topic, String payload);
}
