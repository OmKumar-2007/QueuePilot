package coordinator;

import coordinator.SchedulerService.QueuePreview;
import coordinator.SchedulerSettingsService.SchedulerSettings;
import core.PolicyType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
public class SchedulerController {

    private final SchedulerService scheduler;
    private final SchedulerSettingsService settings;

    public SchedulerController(SchedulerService scheduler, SchedulerSettingsService settings) {
        this.scheduler = scheduler;
        this.settings = settings;
    }

    @GetMapping("/api/scheduler")
    public QueuePreview preview() {
        return scheduler.preview();
    }

    @GetMapping("/api/scheduler/policies")
    public List<PolicyInfo> policies() {
        return Arrays.stream(PolicyType.values()).map(p -> new PolicyInfo(p, p.displayName())).toList();
    }

    @PutMapping("/api/admin/scheduler")
    public SchedulerSettings update(@RequestBody SchedulerSettings requested) {
        return settings.update(requested);
    }

    public record PolicyInfo(PolicyType type, String displayName) {
    }
}
