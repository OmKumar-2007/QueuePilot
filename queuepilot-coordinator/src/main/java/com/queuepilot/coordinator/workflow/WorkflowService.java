package com.queuepilot.coordinator.workflow;

import com.queuepilot.coordinator.error.Errors.NotFoundException;
import com.queuepilot.coordinator.error.Errors.ValidationException;
import com.queuepilot.coordinator.event.EventLog;
import com.queuepilot.coordinator.event.EventType;
import com.queuepilot.coordinator.job.JobEntity;
import com.queuepilot.coordinator.job.JobRepository;
import com.queuepilot.coordinator.job.JobService;
import com.queuepilot.coordinator.job.SubmitJobRequest;
import com.queuepilot.core.job.JobState;
import com.queuepilot.core.schema.ValidationError;
import com.queuepilot.core.workflow.DependencyGraph;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Submits a whole dependency graph in one transaction (F12, F20): steps reference each other by
 * key, the graph is checked for cycles, and jobs are created in topological order so each step's
 * prerequisites already exist when it is created.
 */
@Service
public class WorkflowService {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int MAX_STEPS = 100;

    private final WorkflowRepository workflows;
    private final JobRepository jobs;
    private final JobService jobService;
    private final EventLog events;
    private final Clock clock;

    public WorkflowService(WorkflowRepository workflows, JobRepository jobs, JobService jobService, EventLog events, Clock clock) {
        this.workflows = workflows;
        this.jobs = jobs;
        this.jobService = jobService;
        this.events = events;
        this.clock = clock;
    }

    public record StepRequest(String key, String taskType, Map<String, Object> input, Integer priority, Long estimatedMs,
                              List<String> dependsOn) {
    }

    public record SubmitWorkflowRequest(String name, String queue, List<StepRequest> steps) {
    }

    @Transactional
    public WorkflowView submit(SubmitWorkflowRequest request) {
        List<StepRequest> steps = request.steps() == null ? List.of() : request.steps();
        List<ValidationError> errors = new ArrayList<>();
        String name = request.name() == null || request.name().isBlank() ? "workflow" : request.name().trim();
        if (name.length() > 128) {
            errors.add(new ValidationError("name", "must be at most 128 characters"));
        }
        if (steps.isEmpty() || steps.size() > MAX_STEPS) {
            errors.add(new ValidationError("steps", "must contain 1.." + MAX_STEPS + " steps"));
        }
        Map<String, StepRequest> byKey = new HashMap<>();
        DependencyGraph graph = new DependencyGraph();
        for (StepRequest step : steps) {
            if (step.key() == null || !KEY.matcher(step.key()).matches()) {
                errors.add(new ValidationError("steps", "step key '" + step.key() + "' is invalid"));
            } else if (byKey.put(step.key(), step) != null) {
                errors.add(new ValidationError("steps", "step key '" + step.key() + "' is used twice"));
            } else {
                graph.addNode(step.key());
            }
        }
        for (StepRequest step : steps) {
            for (String dependency : step.dependsOn() == null ? List.<String>of() : step.dependsOn()) {
                if (!byKey.containsKey(dependency)) {
                    errors.add(new ValidationError("steps." + step.key() + ".dependsOn", "unknown step '" + dependency + "'"));
                } else {
                    graph.addDependency(step.key(), dependency);
                }
            }
        }
        if (errors.isEmpty()) {
            graph.findCycle().ifPresent(cycle ->
                    errors.add(new ValidationError("steps", "dependency cycle: " + String.join(" -> ", cycle))));
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }

        WorkflowEntity workflow = workflows.save(new WorkflowEntity(UUID.randomUUID().toString(), name, clock.instant()));
        Map<String, String> jobIdByKey = new HashMap<>();
        for (String key : graph.topologicalOrder()) {
            StepRequest step = byKey.get(key);
            List<String> prerequisiteIds = graph.prerequisitesOf(key).stream().map(jobIdByKey::get).toList();
            SubmitJobRequest jobRequest = new SubmitJobRequest(step.taskType(), step.input(), step.priority(),
                    step.estimatedMs(), request.queue(), prerequisiteIds);
            try {
                jobIdByKey.put(key, jobService.create(jobRequest, workflow.getId(), key).getId());
            } catch (ValidationException e) {
                throw new ValidationException(e.errors().stream()
                        .map(err -> new ValidationError("steps." + key + "." + err.field(), err.message())).toList());
            }
        }
        events.record(EventType.WORKFLOW_SUBMITTED, null, null, null,
                "Workflow " + name + " submitted with " + steps.size() + " steps");
        return view(workflow);
    }

    @Transactional(readOnly = true)
    public WorkflowView get(String id) {
        return view(workflows.findById(id).orElseThrow(() -> new NotFoundException("No workflow " + id)));
    }

    @Transactional(readOnly = true)
    public List<WorkflowView> list(int limit) {
        return workflows.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 200))))
                .stream().map(this::view).toList();
    }

    private WorkflowView view(WorkflowEntity workflow) {
        List<JobEntity> members = jobs.findByWorkflowIdOrderBySubmittedAtAsc(workflow.getId());
        Set<String> memberIds = new HashSet<>();
        members.forEach(job -> memberIds.add(job.getId()));
        Map<JobState, Integer> counts = new EnumMap<>(JobState.class);
        List<WorkflowView.Node> nodes = new ArrayList<>();
        List<WorkflowView.Edge> edges = new ArrayList<>();
        int terminal = 0;
        for (JobEntity job : members) {
            counts.merge(job.getState(), 1, Integer::sum);
            if (job.getState().isTerminal()) {
                terminal++;
            }
            nodes.add(new WorkflowView.Node(job.getId(), job.getWorkflowStep(), job.getTaskType(),
                    job.getState(), job.getProgressPercent()));
            for (String prerequisite : job.getPrerequisiteIds()) {
                if (memberIds.contains(prerequisite)) {
                    edges.add(new WorkflowView.Edge(prerequisite, job.getId()));
                }
            }
        }
        int percent = members.isEmpty() ? 0 : 100 * terminal / members.size();
        return new WorkflowView(workflow.getId(), workflow.getName(), workflow.getCreatedAt(),
                WorkflowView.overallState(counts, members.size()), percent, counts, nodes, edges);
    }
}
