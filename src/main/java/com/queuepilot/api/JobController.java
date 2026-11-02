package com.queuepilot.api;

import com.queuepilot.dispatch.DispatchCoordinator;
import com.queuepilot.dispatch.JobQueueManager;
import com.queuepilot.dto.JobResponse;
import com.queuepilot.dto.SubmitJobRequest;
import com.queuepilot.model.Job;
import com.queuepilot.model.JobState;
import com.queuepilot.model.JobType;
import com.queuepilot.model.MathComputeJob;
import com.queuepilot.model.SortingJob;
import com.queuepilot.model.TextAnalysisJob;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/jobs")
@CrossOrigin(origins = "*")
public class JobController {

    private final JobQueueManager jobQueueManager;
    private final DispatchCoordinator dispatchCoordinator;

    @Autowired
    public JobController(JobQueueManager jobQueueManager, DispatchCoordinator dispatchCoordinator) {
        this.jobQueueManager = jobQueueManager;
        this.dispatchCoordinator = dispatchCoordinator;
    }

    @PostMapping
    public ResponseEntity<JobResponse> submitJob(@RequestBody SubmitJobRequest request) {
        if (request == null || request.getType() == null) {
            return ResponseEntity.badRequest().build();
        }

        Job job = createPolymorphicJob(request);
        if (request.getDependencies() != null) {
            for (String depId : request.getDependencies()) {
                job.addDependency(depId);
            }
        }
        if (request.getScheduledStartTime() != null) {
            job.setScheduledStartTime(request.getScheduledStartTime());
        }
        if (request.getDeadline() != null) {
            job.setDeadline(request.getDeadline());
        }

        jobQueueManager.submitJob(job);
        dispatchCoordinator.dispatchCycle();

        return ResponseEntity.status(HttpStatus.CREATED).body(JobResponse.from(job));
    }

    @GetMapping
    public ResponseEntity<List<JobResponse>> getAllJobs(@RequestParam(required = false) String state) {
        List<Job> allJobs = jobQueueManager.getAllJobs();
        if (state != null && !state.isBlank()) {
            try {
                JobState filterState = JobState.valueOf(state.toUpperCase());
                allJobs = allJobs.stream()
                        .filter(j -> j.getState() == filterState)
                        .collect(Collectors.toList());
            } catch (IllegalArgumentException ignored) {}
        }
        return ResponseEntity.ok(allJobs.stream().map(JobResponse::from).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobResponse> getJobById(@PathVariable String id) {
        return jobQueueManager.getJob(id)
                .map(job -> ResponseEntity.ok(JobResponse.from(job)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancelJob(@PathVariable String id, @RequestParam(defaultValue = "Cancelled by client") String reason) {
        boolean cancelled = jobQueueManager.cancelJob(id, reason);
        if (cancelled) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> clearJobs() {
        jobQueueManager.clear();
        return ResponseEntity.noContent().build();
    }

    private Job createPolymorphicJob(SubmitJobRequest req) {
        JobType type = req.getType();
        String name = req.getName();
        int priority = req.getPriority();
        long durationMs = req.getEstimatedDurationMs();

        return switch (type) {
            case SORTING -> new SortingJob(
                    name, priority, durationMs,
                    null, req.getAlgorithm()
            );
            case TEXT_ANALYSIS -> new TextAnalysisJob(
                    name, priority, durationMs,
                    req.getTextCorpus(), req.getTargetKeywords(), "WORD_FREQUENCY"
            );
            case MATH_COMPUTE -> new MathComputeJob(
                    name, priority, durationMs,
                    req.getComputationType(),
                    req.getInputParam() != null ? req.getInputParam() : 50_000_000L
            );
        };
    }
}
