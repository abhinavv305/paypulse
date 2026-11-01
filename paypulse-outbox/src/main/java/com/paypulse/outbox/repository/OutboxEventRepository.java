package com.paypulse.outbox.repository;

import com.paypulse.outbox.model.OutboxEvent;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")}) // SKIP LOCKED
    @Query("SELECT e FROM OutboxEvent e WHERE e.status = :status ORDER BY e.createdAt ASC")
    List<OutboxEvent> findPendingEventsForProcessing(@Param("status") String status, Pageable pageable);

    List<OutboxEvent> findByAggregateIdOrderByCreatedAtAsc(UUID aggregateId);
}
