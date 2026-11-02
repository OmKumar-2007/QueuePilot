package com.queuepilot.persistence;

import com.queuepilot.model.Job;
import com.queuepilot.model.JobResult;
import com.queuepilot.model.SortingJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AuditServiceTest {

    @Autowired
    private AuditService auditService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobAttemptRepository attemptRepository;

    @Autowired
    private SystemEventRepository eventRepository;

    @Test
    @DisplayName("AuditService should persist job lifecycle, attempts, and system audit events into DB")
    void testAuditPersistence() {
        Job job = new SortingJob("AuditTestJob", 5, 1000L, List.of(3, 2, 1), "QUICKSORT");

        // 1. Record job creation
        auditService.recordJobCreated(job);
        assertTrue(jobRepository.findById(job.getId()).isPresent());
        JobEntity createdEntity = jobRepository.findById(job.getId()).get();
        assertEquals("AuditTestJob", createdEntity.getName());
        assertEquals("QUEUED", createdEntity.getState());

        // 2. Record attempt started
        job.markRunning("worker-1", "token-xyz", java.time.Instant.now().plusSeconds(5));
        auditService.recordJobAttemptStarted(job, "worker-1", "token-xyz");
        var attempts = attemptRepository.findByJobIdOrderByAttemptNumberAsc(job.getId());
        assertEquals(1, attempts.size());
        assertEquals("worker-1", attempts.get(0).getWorkerId());
        assertEquals("RUNNING", attempts.get(0).getStatus());

        // 3. Record job completed
        job.markCompleted(JobResult.success("Done", 500).build());
        auditService.recordJobCompleted(job, job.getResult());
        JobEntity completedEntity = jobRepository.findById(job.getId()).get();
        assertEquals("COMPLETED", completedEntity.getState());

        var updatedAttempts = attemptRepository.findByJobIdOrderByAttemptNumberAsc(job.getId());
        assertEquals("COMPLETED", updatedAttempts.get(0).getStatus());

        // 4. Verify system events
        var events = eventRepository.findTop50ByOrderByTimestampDesc();
        assertFalse(events.isEmpty());
        assertTrue(events.stream().anyMatch(e -> "JOB_COMPLETED".equals(e.getEventType())));
    }
}
