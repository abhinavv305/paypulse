package com.paypulse.outbox.relay;

import com.paypulse.outbox.model.OutboxEvent;
import com.paypulse.outbox.publisher.EventMessagePublisher;
import com.paypulse.outbox.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Service
public class OutboxRelayWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayWorker.class);

    private final OutboxEventRepository repository;
    private final EventMessagePublisher messagePublisher;
    private final TransactionTemplate transactionTemplate;

    public OutboxRelayWorker(
            OutboxEventRepository repository,
            EventMessagePublisher messagePublisher,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.messagePublisher = messagePublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${paypulse.outbox.poll-interval-ms:500}")
    public int relayPendingEvents() {
        return transactionTemplate.execute(status -> {
            List<OutboxEvent> batch = repository.findPendingEventsForProcessing("PENDING", PageRequest.of(0, 50));
            if (batch.isEmpty()) {
                return 0;
            }
            int processed = 0;
            for (OutboxEvent event : batch) {
                try {
                    String topic = "events." + event.getAggregateType().toLowerCase() + "." + event.getEventType().toLowerCase();
                    messagePublisher.publish(event.getEventType(), topic, event.getPayload());
                    event.markPublished();
                    repository.save(event);
                    processed++;
                } catch (Exception ex) {
                    log.error("Failed to relay outbox event {}: {}", event.getId(), ex.getMessage());
                    event.recordFailure(ex.getMessage());
                    repository.save(event);
                }
            }
            return processed;
        });
    }
}
