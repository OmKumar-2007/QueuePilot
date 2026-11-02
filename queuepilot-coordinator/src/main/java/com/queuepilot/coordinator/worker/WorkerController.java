package com.queuepilot.coordinator.worker;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only worker monitoring plus administrator controls. */
@RestController
public class WorkerController {

    private final WorkerRegistryService registry;

    public WorkerController(WorkerRegistryService registry) {
        this.registry = registry;
    }

    @GetMapping("/api/workers")
    public List<WorkerView> list() {
        return registry.list();
    }

    /** action = drain | resume | disable | enable */
    @PostMapping("/api/admin/workers/{id}/{action:drain|resume|disable|enable}")
    public void changeStatus(@PathVariable String id, @PathVariable String action) {
        registry.changeStatus(id, action);
    }

    /** kind = kill | freeze */
    @PostMapping("/api/admin/workers/{id}/simulate/{kind}")
    public void simulate(@PathVariable String id, @PathVariable String kind) {
        registry.simulateFailure(id, kind);
    }
}
