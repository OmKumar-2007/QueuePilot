package com.queuepilot.coordinator.metrics;

import com.queuepilot.coordinator.event.EventLog;
import com.queuepilot.coordinator.event.EventLog.EventView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
public class MetricsController {

    private final MetricsService metrics;
    private final EventLog events;
    private final JdbcTemplate jdbc;

    public MetricsController(MetricsService metrics, EventLog events, JdbcTemplate jdbc) {
        this.metrics = metrics;
        this.events = events;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/metrics/overview")
    public MetricsService.Overview overview() {
        return metrics.overview();
    }

    @GetMapping("/api/events")
    public List<EventView> events(@RequestParam(defaultValue = "100") int limit,
                                  @RequestParam(required = false) Long beforeId) {
        return events.recent(limit, beforeId);
    }

    /** Liveness plus a real database round trip. */
    @GetMapping("/api/health")
    public Health health() {
        boolean databaseUp;
        try {
            databaseUp = Integer.valueOf(1).equals(jdbc.queryForObject("select 1", Integer.class));
        } catch (RuntimeException e) {
            databaseUp = false;
        }
        return new Health(databaseUp ? "UP" : "DEGRADED", databaseUp, "1.0.0", Instant.now());
    }

    public record Health(String status, boolean database, String version, Instant time) {
    }
}
