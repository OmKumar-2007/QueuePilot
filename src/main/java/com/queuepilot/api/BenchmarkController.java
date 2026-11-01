package com.queuepilot.api;

import com.queuepilot.benchmark.BenchmarkService;
import com.queuepilot.benchmark.DemoWorkloadService;
import com.queuepilot.dto.BenchmarkResultDto;
import com.queuepilot.dto.JobResponse;
import com.queuepilot.model.Job;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class BenchmarkController {

    private final BenchmarkService benchmarkService;
    private final DemoWorkloadService demoWorkloadService;

    @Autowired
    public BenchmarkController(BenchmarkService benchmarkService, DemoWorkloadService demoWorkloadService) {
        this.benchmarkService = benchmarkService;
        this.demoWorkloadService = demoWorkloadService;
    }

    @PostMapping("/benchmarks/run")
    public ResponseEntity<List<BenchmarkResultDto>> runBenchmarks() {
        List<BenchmarkResultDto> results = benchmarkService.runComparativeBenchmark();
        return ResponseEntity.ok(results);
    }

    @PostMapping("/demo/seed")
    public ResponseEntity<List<JobResponse>> seedDemoWorkload() {
        List<Job> seededJobs = demoWorkloadService.seedDemoWorkload();
        return ResponseEntity.ok(seededJobs.stream().map(JobResponse::from).toList());
    }
}
