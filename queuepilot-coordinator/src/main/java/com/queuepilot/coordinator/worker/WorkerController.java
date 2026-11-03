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
    private final WorkerLauncher launcher;

    public WorkerController(WorkerRegistryService registry, WorkerLauncher launcher) {
        this.registry = registry;
        this.launcher = launcher;
    }

    /** Starts one more worker process on this machine (demo convenience). */
    @PostMapping("/api/admin/workers/launch")
    public WorkerLauncher.Launched launch(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "2") int slots) {
        return launcher.launch(slots);
    }

    @GetMapping("/api/workers")
    public List<WorkerView> list() {
        return registry.list();
    }

    /**
     * action = drain | pause | resume | interrupt | disable | enable.
     * pause holds running tasks (leases stay alive); interrupt stops running tasks and requeues them.
     */
    @PostMapping("/api/admin/workers/{id}/{action:drain|pause|resume|interrupt|disable|enable}")
    public void changeStatus(@PathVariable String id, @PathVariable String action) {
        registry.changeStatus(id, action);
    }

    /** kind = kill | freeze */
    @PostMapping("/api/admin/workers/{id}/simulate/{kind}")
    public void simulate(@PathVariable String id, @PathVariable String kind) {
        registry.simulateFailure(id, kind);
    }
}
