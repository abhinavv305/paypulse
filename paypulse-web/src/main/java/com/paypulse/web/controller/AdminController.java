package com.paypulse.web.controller;

import com.paypulse.reconciliation.worker.PaymentReconciliationWorker;
import com.paypulse.webhook.service.DlqReplayService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin")
public class AdminController {

    private final PaymentReconciliationWorker reconciliationWorker;
    private final DlqReplayService dlqReplayService;

    public AdminController(
            PaymentReconciliationWorker reconciliationWorker,
            DlqReplayService dlqReplayService) {
        this.reconciliationWorker = reconciliationWorker;
        this.dlqReplayService = dlqReplayService;
    }

    @PostMapping("/reconciliation/run")
    public ResponseEntity<Map<String, Object>> runReconciliation(
            @RequestParam(name = "olderThanSeconds", defaultValue = "300") long olderThanSeconds) {
        int healedCount = reconciliationWorker.reconcileStuckPaymentsInternal(Duration.ofSeconds(olderThanSeconds));
        return ResponseEntity.ok(Map.of(
            "status", "COMPLETED",
            "healedPaymentsCount", healedCount
        ));
    }

    @PostMapping("/dlq/{id}/replay")
    public ResponseEntity<DlqReplayService.ReplayResult> replayDlqEntry(
            @PathVariable("id") UUID id,
            @RequestHeader(name = "X-Admin-User", defaultValue = "admin") String adminUser) {
        return ResponseEntity.ok(dlqReplayService.replayEvent(id, adminUser));
    }

    @PostMapping("/dlq/replay-batch")
    public ResponseEntity<List<DlqReplayService.ReplayResult>> replayDlqBatch(
            @RequestHeader(name = "X-Admin-User", defaultValue = "admin") String adminUser) {
        return ResponseEntity.ok(dlqReplayService.replayAllPending(adminUser));
    }
}
