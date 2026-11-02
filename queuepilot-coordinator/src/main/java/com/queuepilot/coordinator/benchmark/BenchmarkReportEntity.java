package com.queuepilot.coordinator.benchmark;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A report produced by the separate queuepilot-benchmarks module and imported for display. */
@Entity
@Table(name = "benchmark_report")
public class BenchmarkReportEntity {

    @Id
    private String id;
    private String kind;
    private String name;
    private String configJson;
    private String environmentJson;
    private String summaryJson;
    private Instant createdAt;

    protected BenchmarkReportEntity() {
    }

    public BenchmarkReportEntity(String id, String kind, String name, String configJson, String environmentJson,
                                 String summaryJson, Instant createdAt) {
        this.id = id;
        this.kind = kind;
        this.name = name;
        this.configJson = configJson;
        this.environmentJson = environmentJson;
        this.summaryJson = summaryJson;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getKind() { return kind; }
    public String getName() { return name; }
    public String getConfigJson() { return configJson; }
    public String getEnvironmentJson() { return environmentJson; }
    public String getSummaryJson() { return summaryJson; }
    public Instant getCreatedAt() { return createdAt; }
}
