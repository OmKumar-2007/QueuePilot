package com.queuepilot.coordinator.benchmark;

import com.queuepilot.coordinator.config.JsonCodec;
import com.queuepilot.coordinator.error.Errors.NotFoundException;
import com.queuepilot.coordinator.error.Errors.ValidationException;
import com.queuepilot.coordinator.event.EventLog;
import com.queuepilot.coordinator.event.EventType;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stores and serves benchmark reports. The coordinator never runs benchmarks itself (master prompt §6);
 * it only displays what queuepilot-benchmarks produced, labelled SIMULATED or MEASURED.
 */
@RestController
public class BenchmarkController {

    private static final Set<String> KINDS = Set.of("SIMULATED", "MEASURED");
    private static final int MAX_JSON = 300_000;

    private final BenchmarkReportRepository reports;
    private final JsonCodec json;
    private final EventLog events;
    private final Clock clock;

    public BenchmarkController(BenchmarkReportRepository reports, JsonCodec json, EventLog events, Clock clock) {
        this.reports = reports;
        this.json = json;
        this.events = events;
        this.clock = clock;
    }

    public record ReportRequest(String kind, String name, Map<String, Object> config, Map<String, Object> environment,
                                Map<String, Object> summary) {
    }

    public record ReportView(String id, String kind, String name, Map<String, Object> config,
                             Map<String, Object> environment, Map<String, Object> summary, Instant createdAt) {
    }

    @GetMapping("/api/benchmarks")
    public List<ReportView> list() {
        return reports.findAllByOrderByCreatedAtDesc().stream().map(this::toView).toList();
    }

    @GetMapping("/api/benchmarks/{id}")
    public ReportView get(@PathVariable String id) {
        return toView(reports.findById(id).orElseThrow(() -> new NotFoundException("No benchmark report " + id)));
    }

    @PostMapping("/api/admin/benchmarks")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ReportView importReport(@RequestBody ReportRequest request) {
        if (request.kind() == null || !KINDS.contains(request.kind())) {
            throw new ValidationException("kind", "must be SIMULATED or MEASURED");
        }
        if (request.name() == null || request.name().isBlank() || request.name().length() > 128 || request.summary() == null) {
            throw new ValidationException("name", "name (max 128 chars) and summary are required");
        }
        String config = json.write(request.config() == null ? Map.of() : request.config());
        String environment = json.write(request.environment() == null ? Map.of() : request.environment());
        String summary = json.write(request.summary());
        if (config.length() > MAX_JSON || environment.length() > MAX_JSON || summary.length() > MAX_JSON) {
            throw new ValidationException("summary", "report is too large");
        }
        BenchmarkReportEntity entity = reports.save(new BenchmarkReportEntity(UUID.randomUUID().toString(),
                request.kind(), request.name(), config, environment, summary, clock.instant()));
        events.record(EventType.BENCHMARK_IMPORTED, null, null, null, request.kind() + " benchmark '" + request.name() + "' imported");
        return toView(entity);
    }

    private ReportView toView(BenchmarkReportEntity e) {
        return new ReportView(e.getId(), e.getKind(), e.getName(), json.readMap(e.getConfigJson()),
                json.readMap(e.getEnvironmentJson()), json.readMap(e.getSummaryJson()), e.getCreatedAt());
    }
}
