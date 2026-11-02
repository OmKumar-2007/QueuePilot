package com.queuepilot.api;

import com.queuepilot.dispatch.DispatchCoordinator;
import com.queuepilot.scheduler.SchedulerPolicyFactory;
import com.queuepilot.scheduler.SchedulingPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scheduler")
@CrossOrigin(origins = "*")
public class SchedulerController {

    private final SchedulerPolicyFactory policyFactory;
    private final DispatchCoordinator dispatchCoordinator;

    @Autowired
    public SchedulerController(SchedulerPolicyFactory policyFactory, DispatchCoordinator dispatchCoordinator) {
        this.policyFactory = policyFactory;
        this.dispatchCoordinator = dispatchCoordinator;
    }

    @GetMapping("/policy")
    public ResponseEntity<Map<String, String>> getActivePolicy() {
        return ResponseEntity.ok(Map.of(
                "activePolicy", policyFactory.getActivePolicyName(),
                "description", policyFactory.getActivePolicy().getPolicyName()
        ));
    }

    @PostMapping("/policy")
    public ResponseEntity<Map<String, String>> setActivePolicy(@RequestParam(required = false) String name,
                                                               @RequestBody(required = false) Map<String, String> body) {
        String policyName = name;
        if (policyName == null && body != null) {
            policyName = body.get("policy");
        }
        if (policyName == null || policyName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Policy name is required"));
        }

        try {
            policyFactory.setActivePolicyName(policyName);
            dispatchCoordinator.dispatchCycle(); // Re-evaluate queue under newly selected policy
            return ResponseEntity.ok(Map.of(
                    "activePolicy", policyFactory.getActivePolicyName(),
                    "message", "Successfully switched active policy to " + policyFactory.getActivePolicyName()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/policies")
    public ResponseEntity<List<Map<String, String>>> getAvailablePolicies() {
        List<Map<String, String>> list = new ArrayList<>();
        Map<String, String> descriptions = Map.of(
                "FCFS", "First Come, First Served - dispatches in chronological order of arrival",
                "SJF", "Shortest Job First - prioritizes tasks with lowest estimated runtime",
                "PRIORITY_AGING", "Priority with Aging - dynamically increases priority over queue wait time",
                "MLFQ", "Multi-Level Feedback Queue - multi-tiered queues with demotion and periodic boost"
        );

        for (String policyName : policyFactory.getAvailablePolicies().keySet()) {
            list.add(Map.of(
                    "name", policyName,
                    "description", descriptions.getOrDefault(policyName, policyName)
            ));
        }
        return ResponseEntity.ok(list);
    }

    @PostMapping("/dispatch")
    public ResponseEntity<String> triggerDispatch() {
        dispatchCoordinator.dispatchCycle();
        return ResponseEntity.ok("Dispatch cycle triggered.");
    }
}
