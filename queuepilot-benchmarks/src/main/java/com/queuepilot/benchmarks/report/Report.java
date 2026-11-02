package com.queuepilot.benchmarks.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything a benchmark run produces: configuration, environment, summary and raw rows. */
public class Report {

    public final String kind;
    public final String name;
    public final Map<String, Object> config = new LinkedHashMap<>();
    public final Map<String, Object> environment = new LinkedHashMap<>();
    public final Map<String, Object> summary = new LinkedHashMap<>();
    public List<String> rawHeader = List.of();
    public final List<List<Object>> raw = new ArrayList<>();

    public Report(String kind, String name) {
        this.kind = kind;
        this.name = name;
    }
}
