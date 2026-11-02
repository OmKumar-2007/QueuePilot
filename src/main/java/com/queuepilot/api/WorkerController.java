package com.queuepilot.api;

import com.queuepilot.dto.WorkerResponse;
import com.queuepilot.recovery.RecoveryWatchdog;
import com.queuepilot.worker.WorkerPool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/workers")
@CrossOrigin(origins = "*")
public class WorkerController {

    private final WorkerPool workerPool;
    private final RecoveryWatchdog recoveryWatchdog;

    @Autowired
    public WorkerController(WorkerPool workerPool, RecoveryWatchdog recoveryWatchdog) {
        this.workerPool = workerPool;
        this.recoveryWatchdog = recoveryWatchdog;
    }

    @GetMapping
    public ResponseEntity<List<WorkerResponse>> getAllWorkers() {
        return ResponseEntity.ok(
                workerPool.getAllWorkerModels().stream()
                        .map(WorkerResponse::from)
                        .toList()
        );
    }

    @PostMapping("/{id}/simulate-crash")
    public ResponseEntity<String> simulateCrash(@PathVariable String id) {
        boolean crashed = workerPool.simulateCrash(id);
        if (crashed) {
            // Trigger watchdog scan to demonstrate automatic detection & recovery (Demo Step 5 & 6)
            if (recoveryWatchdog != null) {
                recoveryWatchdog.checkAndRecover();
            }
            return ResponseEntity.ok("Worker " + id + " crash simulated. Watchdog recovery triggered.");
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/simulate-freeze")
    public ResponseEntity<String> simulateFreeze(@PathVariable String id, @RequestParam(defaultValue = "true") boolean freeze) {
        boolean updated = workerPool.simulateFreeze(id, freeze);
        if (updated) {
            return ResponseEntity.ok("Worker " + id + " freeze state set to: " + freeze);
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/revive")
    public ResponseEntity<String> reviveWorker(@PathVariable String id) {
        boolean revived = workerPool.reviveWorker(id);
        if (revived) {
            return ResponseEntity.ok("Worker " + id + " successfully revived and ready for tasks.");
        }
        return ResponseEntity.notFound().build();
    }
}
