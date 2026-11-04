package com.queuepilot.coordinator.workflow;

import com.queuepilot.coordinator.workflow.WorkflowService.SubmitWorkflowRequest;
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

@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowService workflows;

    public WorkflowController(WorkflowService workflows) {
        this.workflows = workflows;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowView submit(@RequestBody SubmitWorkflowRequest request) {
        return workflows.submit(request);
    }

    @GetMapping
    public List<WorkflowView> list(@RequestParam(defaultValue = "50") int limit) {
        return workflows.list(limit);
    }

    @GetMapping("/{id}")
    public WorkflowView get(@PathVariable String id) {
        return workflows.get(id);
    }
}
