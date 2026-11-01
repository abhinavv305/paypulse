package com.paypulse.webhook.service;

import com.paypulse.webhook.model.DeadLetterEvent;
import com.paypulse.webhook.model.WebhookEvent;
import com.paypulse.webhook.repository.DeadLetterEventRepository;
import com.paypulse.webhook.repository.WebhookEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DlqReplayService {

    private static final Logger log = LoggerFactory.getLogger(DlqReplayService.class);

    private final DeadLetterEventRepository dlqRepository;
    private final WebhookEventRepository webhookRepository;
    private final WebhookProcessingService webhookProcessingService;

    public DlqReplayService(
            DeadLetterEventRepository dlqRepository,
            WebhookEventRepository webhookRepository,
            WebhookProcessingService webhookProcessingService) {
        this.dlqRepository = dlqRepository;
        this.webhookRepository = webhookRepository;
        this.webhookProcessingService = webhookProcessingService;
    }

    public record ReplayResult(UUID dlqId, boolean success, String message) {}

    @Transactional
    public ReplayResult replayEvent(UUID dlqId, String adminUser) {
        Optional<DeadLetterEvent> dlqOpt = dlqRepository.findById(dlqId);
        if (dlqOpt.isEmpty()) {
            return new ReplayResult(dlqId, false, "DLQ entry not found");
        }

        DeadLetterEvent dlq = dlqOpt.get();
        if (dlq.isReplayed()) {
            return new ReplayResult(dlqId, false, "DLQ entry already replayed at " + dlq.getReplayedAt());
        }

        if ("WEBHOOK".equalsIgnoreCase(dlq.getSourceType())) {
            Optional<WebhookEvent> webhookOpt = webhookRepository.findById(dlq.getSourceId());
            if (webhookOpt.isEmpty()) {
                return new ReplayResult(dlqId, false, "Underlying webhook event not found");
            }

            WebhookEvent webhook = webhookOpt.get();
            boolean processed = webhookProcessingService.processSingleEvent(webhook);
            if (processed) {
                dlq.markReplayed(adminUser);
                dlqRepository.save(dlq);
                log.info("Successfully replayed DLQ event {} by user {}", dlqId, adminUser);
                return new ReplayResult(dlqId, true, "Replay succeeded");
            } else {
                return new ReplayResult(dlqId, false, "Replay execution failed during processing");
            }
        }

        return new ReplayResult(dlqId, false, "Unsupported source type: " + dlq.getSourceType());
    }

    public List<ReplayResult> replayAllPending(String adminUser) {
        List<DeadLetterEvent> pending = dlqRepository.findByReplayedFalseOrderByCreatedAtAsc();
        return pending.stream()
            .map(dlq -> replayEvent(dlq.getId(), adminUser))
            .toList();
    }
}
