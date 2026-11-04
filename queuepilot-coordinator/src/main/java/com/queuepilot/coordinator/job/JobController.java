package com.queuepilot.coordinator.job;

import com.queuepilot.coordinator.artifact.ArtifactController.ArtifactView;
import com.queuepilot.coordinator.artifact.ArtifactService;
import com.queuepilot.coordinator.attempt.AttemptRepository;
import com.queuepilot.coordinator.attempt.AttemptView;
import com.queuepilot.coordinator.event.EventLog;
import com.queuepilot.coordinator.event.EventLog.EventView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Client API for jobs. Thin: validation and rules live in {@link JobService}. */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;
    private final AttemptRepository attempts;
    private final ArtifactService artifacts;
    private final EventLog events;

    public JobController(JobService jobService, AttemptRepository attempts, ArtifactService artifacts, EventLog events) {
        this.jobService = jobService;
        this.attempts = attempts;
        this.artifacts = artifacts;
        this.events = events;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JobView submit(@RequestBody SubmitJobRequest request) {
        return JobView.summary(jobService.submit(request));
    }

    @GetMapping
    public List<JobView> list(@RequestParam(required = false) String state,
                              @RequestParam(defaultValue = "200") int limit) {
        return jobService.list(state, limit).stream().map(JobView::summary).toList();
    }

    @GetMapping("/{id}")
    public JobView get(@PathVariable String id) {
        JobEntity job = jobService.get(id);
        return JobView.of(job, jobService.inputOf(job), jobService.outputOf(job));
    }

    @GetMapping("/{id}/history")
    public JobHistory history(@PathVariable String id) {
        JobEntity job = jobService.get(id);
        return new JobHistory(
                JobView.of(job, jobService.inputOf(job), jobService.outputOf(job)),
                attempts.findByJobIdOrderByAttemptNumberAsc(id).stream().map(AttemptView::from).toList(),
                events.forJob(id),
                artifacts.forJob(id).stream().map(ArtifactView::from).toList());
    }

    @PostMapping("/{id}/cancel")
    public JobView cancel(@PathVariable String id) {
        return JobView.summary(jobService.cancel(id));
    }

    public record JobHistory(JobView job, List<AttemptView> attempts, List<EventView> events, List<ArtifactView> artifacts) {
    }
}
