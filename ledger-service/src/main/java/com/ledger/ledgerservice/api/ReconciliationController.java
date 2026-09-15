package com.ledger.ledgerservice.api;

import com.ledger.ledgerservice.api.dto.ReconciliationRunResponse;
import com.ledger.ledgerservice.domain.ReconciliationRun;
import com.ledger.ledgerservice.reconciliation.ReconciliationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/reconciliation")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @PostMapping("/runs")
    public ResponseEntity<RunSummary> triggerRun() {
        ReconciliationRun run = reconciliationService.runReconciliation();
        return ResponseEntity.ok(new RunSummary(
                run.getId().toString(), run.getStatus().name(),
                run.getEntriesImbalanceCount(), run.getOutboxMissingCount(), run.getOutboxStuckCount()));
    }

    public record RunSummary(String runId, String status, int entriesImbalanceCount,
                              int outboxMissingCount, int outboxStuckCount) {
    }

    @GetMapping("/runs")
    public ResponseEntity<List<ReconciliationRunResponse>> listRuns() {
        return ResponseEntity.ok(reconciliationService.listRuns());
    }
}
