package com.paypulse.webhook.repository;

import com.paypulse.webhook.model.DeadLetterEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeadLetterEventRepository extends JpaRepository<DeadLetterEvent, UUID> {

    List<DeadLetterEvent> findByReplayedFalseOrderByCreatedAtAsc();

    List<DeadLetterEvent> findBySourceTypeAndReplayedFalse(String sourceType);
}
