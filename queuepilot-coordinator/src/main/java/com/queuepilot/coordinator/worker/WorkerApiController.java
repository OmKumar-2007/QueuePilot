package com.queuepilot.coordinator.worker;

import com.queuepilot.coordinator.artifact.ArtifactController.ArtifactView;
import com.queuepilot.coordinator.artifact.ArtifactEntity;
import com.queuepilot.coordinator.artifact.ArtifactService;
import com.queuepilot.coordinator.attempt.AttemptService;
import com.queuepilot.coordinator.attempt.AttemptService.UpdateResult;
import com.queuepilot.coordinator.error.Errors.StaleAttemptException;
import com.queuepilot.coordinator.scheduler.SchedulerService;
import com.queuepilot.core.protocol.WorkerProtocol.Assignment;
import com.queuepilot.core.protocol.WorkerProtocol.CompletionReport;
import com.queuepilot.core.protocol.WorkerProtocol.FailureReport;
import com.queuepilot.core.protocol.WorkerProtocol.HeartbeatRequest;
import com.queuepilot.core.protocol.WorkerProtocol.HeartbeatResponse;
import com.queuepilot.core.protocol.WorkerProtocol.ProgressReport;
import com.queuepilot.core.protocol.WorkerProtocol.RegisterRequest;
import com.queuepilot.core.protocol.WorkerProtocol.RegisterResponse;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The worker protocol (docs/architecture/coordinator-worker-protocol.md). After registering, a worker
 * sends its id in the {@value #WORKER_HEADER} header on every call.
 */
@RestController
@RequestMapping("/api/worker")
public class WorkerApiController {

    public static final String WORKER_HEADER = "X-Worker-Id";

    private final WorkerRegistryService registry;
    private final SchedulerService scheduler;
    private final AttemptService attempts;
    private final ArtifactService artifacts;

    public WorkerApiController(WorkerRegistryService registry, SchedulerService scheduler, AttemptService attempts,
                               ArtifactService artifacts) {
        this.registry = registry;
        this.scheduler = scheduler;
        this.attempts = attempts;
        this.artifacts = artifacts;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@RequestBody RegisterRequest request) {
        return registry.register(request);
    }

    @PostMapping("/claim")
    public ResponseEntity<Assignment> claim(@RequestHeader(WORKER_HEADER) String workerId) {
        return scheduler.claim(workerId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/heartbeat")
    public HeartbeatResponse heartbeat(@RequestHeader(WORKER_HEADER) String workerId, @RequestBody HeartbeatRequest request) {
        return attempts.heartbeat(workerId, request.activeAttemptIds());
    }

    @PostMapping("/attempts/{attemptId}/progress")
    public void progress(@RequestHeader(WORKER_HEADER) String workerId, @PathVariable String attemptId,
                         @RequestBody ProgressReport report) {
        requireAccepted(attempts.progress(workerId, attemptId, report), attemptId);
    }

    @PostMapping("/attempts/{attemptId}/complete")
    public void complete(@RequestHeader(WORKER_HEADER) String workerId, @PathVariable String attemptId,
                         @RequestBody CompletionReport report) {
        requireAccepted(attempts.complete(workerId, attemptId, report), attemptId);
    }

    @PostMapping("/attempts/{attemptId}/fail")
    public void fail(@RequestHeader(WORKER_HEADER) String workerId, @PathVariable String attemptId,
                     @RequestBody FailureReport report) {
        requireAccepted(attempts.fail(workerId, attemptId, report), attemptId);
    }

    @GetMapping("/attempts/{attemptId}/artifacts/{artifactId}")
    public ResponseEntity<InputStreamResource> downloadInput(@RequestHeader(WORKER_HEADER) String workerId,
                                                             @PathVariable String attemptId, @PathVariable String artifactId) {
        ArtifactEntity artifact = attempts.inputForAttempt(workerId, attemptId, artifactId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(artifact.getSizeBytes())
                .body(new InputStreamResource(artifacts.open(artifact)));
    }

    @PostMapping(path = "/attempts/{attemptId}/artifacts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ArtifactView uploadOutput(@RequestHeader(WORKER_HEADER) String workerId, @PathVariable String attemptId,
                                     @RequestParam("file") MultipartFile file) {
        return ArtifactView.from(attempts.uploadOutput(workerId, attemptId, file));
    }

    @PostMapping("/deregister")
    public void deregister(@RequestHeader(WORKER_HEADER) String workerId) {
        registry.deregister(workerId);
    }

    private static void requireAccepted(UpdateResult result, String attemptId) {
        if (result == UpdateResult.STALE) {
            throw new StaleAttemptException("Attempt " + attemptId + " is no longer current; stop working on it");
        }
    }
}
