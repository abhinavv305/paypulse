package com.paypulse.outbox.publisher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class InMemoryEventMessagePublisher implements EventMessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventMessagePublisher.class);
    private final List<PublishedMessage> publishedMessages = new CopyOnWriteArrayList<>();

    public record PublishedMessage(String eventType, String topic, String payload) {}

    @Override
    public void publish(String eventType, String topic, String payload) {
        log.info("Dispatched outbox event [{}]: {}", eventType, payload);
        publishedMessages.add(new PublishedMessage(eventType, topic, payload));
    }

    public List<PublishedMessage> getPublishedMessages() {
        return publishedMessages;
    }

    public void clear() {
        publishedMessages.clear();
    }
}
