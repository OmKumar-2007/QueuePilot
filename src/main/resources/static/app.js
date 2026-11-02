/**
 * QueuePilot Dashboard Application
 * Handles real-time polling, worker control, fault simulation, and benchmark visualizations.
 */

document.addEventListener("DOMContentLoaded", () => {
    // --- State ---
    let currentJobs = [];
    let currentWorkers = [];
    let activePolicy = "FCFS";

    // --- DOM Elements ---
    const policySelect = document.getElementById("policySelect");
    const btnSeedDemo = document.getElementById("btnSeedDemo");
    const btnNewJob = document.getElementById("btnNewJob");
    const btnClearJobs = document.getElementById("btnClearJobs");
    const btnRunBenchmark = document.getElementById("btnRunBenchmark");

    const statTotalJobs = document.getElementById("statTotalJobs");
    const statRunningJobs = document.getElementById("statRunningJobs");
    const statQueuedJobs = document.getElementById("statQueuedJobs");
    const statBlockedJobs = document.getElementById("statBlockedJobs");
    const statCompletedJobs = document.getElementById("statCompletedJobs");
    const statRecoveredJobs = document.getElementById("statRecoveredJobs");

    const workersGrid = document.getElementById("workersGrid");
    const jobsTableBody = document.getElementById("jobsTableBody");
    const eventStreamBody = document.getElementById("eventStreamBody");

    const jobModal = document.getElementById("jobModal");
    const btnCloseModal = document.getElementById("btnCloseModal");
    const btnCancelModal = document.getElementById("btnCancelModal");
    const jobForm = document.getElementById("jobForm");
    const inputDurationRange = document.getElementById("inputDurationRange");
    const durationDisplay = document.getElementById("durationDisplay");

    const detailsModal = document.getElementById("detailsModal");
    const btnCloseDetailsModal = document.getElementById("btnCloseDetailsModal");
    const detailsModalBody = document.getElementById("detailsModalBody");

    // Range display update
    inputDurationRange.addEventListener("input", (e) => {
        durationDisplay.textContent = e.target.value;
    });

    // --- Tab Navigation ---
    document.querySelectorAll(".tab-btn").forEach(btn => {
        btn.addEventListener("click", () => {
            document.querySelectorAll(".tab-btn").forEach(b => b.classList.remove("active"));
            document.querySelectorAll(".tab-content").forEach(c => c.classList.remove("active"));
            btn.classList.add("active");
            document.getElementById(btn.dataset.tab).classList.add("active");
        });
    });

    // --- Polling Cycle ---
    async function refreshDashboard() {
        try {
            const [jobsRes, workersRes, policyRes, eventsRes] = await Promise.all([
                fetch("/api/jobs"),
                fetch("/api/workers"),
                fetch("/api/scheduler/policy"),
                fetch("/api/events")
            ]);

            if (jobsRes.ok) currentJobs = await jobsRes.json();
            if (workersRes.ok) currentWorkers = await workersRes.json();
            if (policyRes.ok) {
                const polData = await policyRes.json();
                activePolicy = polData.activePolicy;
                if (document.activeElement !== policySelect) {
                    policySelect.value = activePolicy;
                }
            }

            renderStats(currentJobs);
            renderWorkers(currentWorkers, currentJobs);
            renderJobsTable(currentJobs);

            if (eventsRes.ok) {
                const events = await eventsRes.json();
                renderEvents(events);
            }
        } catch (err) {
            console.error("Dashboard refresh error:", err);
        }
    }

    // --- Render Functions ---

    function renderStats(jobs) {
        statTotalJobs.textContent = jobs.length;
        statRunningJobs.textContent = jobs.filter(j => j.state === "RUNNING").length;
        statQueuedJobs.textContent = jobs.filter(j => j.state === "QUEUED").length;
        statBlockedJobs.textContent = jobs.filter(j => j.state === "BLOCKED").length;
        statCompletedJobs.textContent = jobs.filter(j => j.state === "COMPLETED").length;
        statRecoveredJobs.textContent = jobs.reduce((sum, j) => sum + (j.retryCount || 0), 0);
    }

    function renderWorkers(workers, jobs) {
        workersGrid.innerHTML = "";
        workers.forEach(worker => {
            const card = document.createElement("div");
            card.className = `worker-card worker-${worker.status.toLowerCase()}`;

            const activeJob = jobs.find(j => j.id === worker.currentJobId);
            const isDead = worker.status === "DEAD";

            const caps = Array.from(worker.capabilities || [])
                .map(c => `<span class="cap-tag">${c.replace("TAG_", "")}</span>`)
                .join(" ");

            let activeJobHtml = '<span class="text-muted">Idle - Ready for tasks</span>';
            if (activeJob) {
                activeJobHtml = `
                    <div style="font-weight: 600; font-size: 0.85rem; margin-bottom: 0.2rem;">${activeJob.name}</div>
                    <div class="progress-container">
                        <div class="progress-bar" style="width: ${activeJob.progressPercent}%;"></div>
                    </div>
                    <div style="display: flex; justify-content: space-between; font-size: 0.7rem; color: var(--text-secondary);">
                        <span>${activeJob.progressMessage || "Executing..."}</span>
                        <span>${activeJob.progressPercent}%</span>
                    </div>
                `;
            } else if (isDead) {
                activeJobHtml = `<span style="color: var(--danger); font-weight: 600;">TERMINATED / CRASHED</span>`;
            }

            card.innerHTML = `
                <div class="worker-card-header">
                    <span class="worker-title">${worker.name}</span>
                    <span class="worker-status-pill status-${worker.status.toLowerCase()}">${worker.status}</span>
                </div>
                <div class="worker-caps">${caps}</div>
                <div class="worker-active-job">
                    ${activeJobHtml}
                </div>
                <div class="worker-heartbeat-bar">
                    <span>Completed: ${worker.totalCompleted} | Failed: ${worker.totalFailed}</span>
                    <span>${isDead ? "Offline" : "Beat: Active"}</span>
                </div>
                <div class="worker-actions">
                    ${isDead ? `
                        <button class="btn btn-sm btn-primary" onclick="reviveWorker('${worker.id}')" style="width: 100%;">
                            💚 Revive Worker
                        </button>
                    ` : `
                        <button class="btn btn-sm btn-danger-outline" onclick="simulateCrash('${worker.id}')" title="Simulate Worker Failure (Demo Step 5)">
                            ⚡ Crash Worker
                        </button>
                        <button class="btn btn-sm btn-secondary" onclick="simulateFreeze('${worker.id}')" title="Freeze heartbeats to test lease expiry">
                            ❄️ Freeze
                        </button>
                    `}
                </div>
            `;
            workersGrid.appendChild(card);
        });
    }

    function renderJobsTable(jobs) {
        if (!jobs || jobs.length === 0) {
            jobsTableBody.innerHTML = `
                <tr>
                    <td colspan="8" class="text-center py-4 text-muted">
                        No jobs in queue. Click "Seed 10 Demo Jobs (10-30s)" to begin demonstration!
                    </td>
                </tr>
            `;
            return;
        }

        jobsTableBody.innerHTML = jobs.map(job => {
            const stateClass = `badge-${job.state.toLowerCase()}`;
            const durationSec = (job.estimatedDurationMs / 1000).toFixed(1);
            const waitTime = job.waitingTimeMs != null ? `${(job.waitingTimeMs / 1000).toFixed(1)}s` : "-";
            const turnaround = job.turnaroundTimeMs != null ? `${(job.turnaroundTimeMs / 1000).toFixed(1)}s` : "-";

            const tierBadge = `<span class="cap-tag">T${job.mlfqLevel}</span>`;
            const retryBadge = job.retryCount > 0 ? `<span style="color: #f0883e; font-weight: 700;">(Retry ${job.retryCount}/${job.maxRetries})</span>` : "";

            return `
                <tr>
                    <td>
                        <div style="font-weight: 600;">${job.name}</div>
                        <div style="font-size: 0.7rem; color: var(--text-secondary);">${job.type} ${retryBadge}</div>
                    </td>
                    <td><span class="badge ${stateClass}">${job.state}</span></td>
                    <td>
                        <span>P:${job.effectivePriority} (Base ${job.basePriority})</span>
                        ${tierBadge}
                    </td>
                    <td>${job.assignedWorkerId || '<span class="text-muted">-</span>'}</td>
                    <td style="min-width: 140px;">
                        <div class="progress-container">
                            <div class="progress-bar" style="width: ${job.progressPercent}%;"></div>
                        </div>
                        <div style="font-size: 0.7rem; color: var(--text-muted);">${job.progressPercent}% - ${job.progressMessage || ""}</div>
                    </td>
                    <td>${job.dependencies && job.dependencies.length > 0 ? `<span class="cap-tag">${job.dependencies.length} prereq</span>` : '<span class="text-muted">None</span>'}</td>
                    <td>
                        <div>Est: ${durationSec}s</div>
                        <div style="font-size: 0.7rem; color: var(--text-muted);">Wait: ${waitTime} | Turn: ${turnaround}</div>
                    </td>
                    <td>
                        <div style="display: flex; gap: 0.35rem;">
                            <button class="btn btn-sm btn-secondary" onclick="viewJobDetails('${job.id}')">Info</button>
                            ${!["COMPLETED", "FAILED", "CANCELLED"].includes(job.state) ? `
                                <button class="btn btn-sm btn-danger-outline" onclick="cancelJob('${job.id}')">Cancel</button>
                            ` : ""}
                        </div>
                    </td>
                </tr>
            `;
        }).join("");
    }

    function renderEvents(events) {
        if (!events || events.length === 0) return;
        eventStreamBody.innerHTML = events.slice(0, 30).map(e => {
            const time = new Date(e.timestamp).toLocaleTimeString();
            return `
                <div class="event-entry">
                    <span>${time}</span>
                    <span style="font-weight: 600; color: var(--primary-hover);">${e.eventType}</span>
                    <span>${e.entityId || "-"}</span>
                    <span>${e.message}</span>
                </div>
            `;
        }).join("");
    }

    // --- Actions ---

    // Policy Change (Demo Step 2)
    policySelect.addEventListener("change", async (e) => {
        const newPolicy = e.target.value;
        try {
            const res = await fetch(`/api/scheduler/policy?name=${encodeURIComponent(newPolicy)}`, { method: "POST" });
            if (res.ok) {
                console.log("Switched policy to:", newPolicy);
                refreshDashboard();
            }
        } catch (err) {
            console.error("Policy switch failed:", err);
        }
    });

    // Seed 10 Demo Jobs (Demo Step 1)
    btnSeedDemo.addEventListener("click", async () => {
        btnSeedDemo.disabled = true;
        btnSeedDemo.textContent = "⏳ Seeding 10 Jobs...";
        try {
            await fetch("/api/demo/seed", { method: "POST" });
            refreshDashboard();
        } finally {
            btnSeedDemo.disabled = false;
            btnSeedDemo.innerHTML = `<span class="btn-icon">🚀</span> Seed 10 Demo Jobs (10-30s)`;
        }
    });

    // Clear Jobs
    btnClearJobs.addEventListener("click", async () => {
        if (confirm("Clear all jobs in queue?")) {
            await fetch("/api/jobs", { method: "DELETE" });
            refreshDashboard();
        }
    });

    // Run Benchmark (Demo Step 7)
    btnRunBenchmark.addEventListener("click", async () => {
        btnRunBenchmark.disabled = true;
        btnRunBenchmark.textContent = "⏳ Evaluating All 4 Algorithms...";
        try {
            const res = await fetch("/api/benchmarks/run", { method: "POST" });
            if (res.ok) {
                const benchmarkResults = await res.json();
                renderBenchmarkVisualizations(benchmarkResults);
            }
        } finally {
            btnRunBenchmark.disabled = false;
            btnRunBenchmark.innerHTML = `<span class="btn-icon">📊</span> Re-Run Benchmark`;
        }
    });

    function renderBenchmarkVisualizations(results) {
        document.getElementById("benchmarkChartsContainer").style.display = "grid";
        document.getElementById("benchmarkTableContainer").style.display = "block";

        // Find max values for normalization
        const maxTurnaround = Math.max(...results.map(r => r.avgTurnaroundTimeMs));
        const maxWait = Math.max(...results.map(r => r.avgWaitingTimeMs));
        const maxThroughput = Math.max(...results.map(r => r.throughputJobsPerSec));

        const getBarColor = (name) => {
            if (name === "FCFS") return "fill-fcfs";
            if (name === "SJF") return "fill-sjf";
            if (name === "PRIORITY_AGING") return "fill-priority";
            return "fill-mlfq";
        };

        // Turnaround chart
        document.getElementById("chartTurnaround").innerHTML = results.map(r => `
            <div class="chart-bar-row">
                <div class="chart-bar-label">
                    <span>${r.policyName}</span>
                    <span>${(r.avgTurnaroundTimeMs / 1000).toFixed(1)}s</span>
                </div>
                <div class="chart-bar-fill-container">
                    <div class="chart-bar-fill ${getBarColor(r.policyName)}" style="width: ${(r.avgTurnaroundTimeMs / maxTurnaround) * 100}%;"></div>
                </div>
            </div>
        `).join("");

        // Waiting time chart
        document.getElementById("chartWaitTime").innerHTML = results.map(r => `
            <div class="chart-bar-row">
                <div class="chart-bar-label">
                    <span>${r.policyName}</span>
                    <span>${(r.avgWaitingTimeMs / 1000).toFixed(1)}s</span>
                </div>
                <div class="chart-bar-fill-container">
                    <div class="chart-bar-fill ${getBarColor(r.policyName)}" style="width: ${(r.avgWaitingTimeMs / maxWait) * 100}%;"></div>
                </div>
            </div>
        `).join("");

        // Throughput chart
        document.getElementById("chartThroughput").innerHTML = results.map(r => `
            <div class="chart-bar-row">
                <div class="chart-bar-label">
                    <span>${r.policyName}</span>
                    <span>${r.throughputJobsPerSec} j/s</span>
                </div>
                <div class="chart-bar-fill-container">
                    <div class="chart-bar-fill ${getBarColor(r.policyName)}" style="width: ${(r.throughputJobsPerSec / maxThroughput) * 100}%;"></div>
                </div>
            </div>
        `).join("");

        // Utilization chart
        document.getElementById("chartUtilization").innerHTML = results.map(r => `
            <div class="chart-bar-row">
                <div class="chart-bar-label">
                    <span>${r.policyName}</span>
                    <span>${r.workerUtilizationPercent}%</span>
                </div>
                <div class="chart-bar-fill-container">
                    <div class="chart-bar-fill ${getBarColor(r.policyName)}" style="width: ${r.workerUtilizationPercent}%;"></div>
                </div>
            </div>
        `).join("");

        // Table
        document.getElementById("benchmarkTableBody").innerHTML = results.map(r => `
            <tr>
                <td style="font-weight: 700;">${r.policyName}</td>
                <td>${r.jobCount}</td>
                <td>${(r.avgWaitingTimeMs / 1000).toFixed(2)}s</td>
                <td>${(r.avgTurnaroundTimeMs / 1000).toFixed(2)}s</td>
                <td>${(r.totalDurationMs / 1000).toFixed(1)}s</td>
                <td>${r.throughputJobsPerSec} jobs/s</td>
                <td>${r.workerUtilizationPercent}%</td>
            </tr>
        `).join("");
    }

    // Modal submit custom job
    btnNewJob.addEventListener("click", () => jobModal.style.display = "flex");
    btnCloseModal.addEventListener("click", () => jobModal.style.display = "none");
    btnCancelModal.addEventListener("click", () => jobModal.style.display = "none");

    jobForm.addEventListener("submit", async (e) => {
        e.preventDefault();
        const req = {
            name: document.getElementById("inputJobName").value,
            type: document.getElementById("inputJobType").value,
            priority: parseInt(document.getElementById("inputPriority").value),
            estimatedDurationMs: parseInt(inputDurationRange.value) * 1000,
            dependencies: []
        };
        const dep = document.getElementById("inputDependency").value.trim();
        if (dep) req.dependencies.push(dep);

        await fetch("/api/jobs", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(req)
        });

        jobModal.style.display = "none";
        jobForm.reset();
        durationDisplay.textContent = "15";
        refreshDashboard();
    });

    btnCloseDetailsModal.addEventListener("click", () => detailsModal.style.display = "none");

    // Global window functions for inline onclicks
    window.simulateCrash = async (workerId) => {
        await fetch(`/api/workers/${workerId}/simulate-crash`, { method: "POST" });
        refreshDashboard();
    };

    window.simulateFreeze = async (workerId) => {
        await fetch(`/api/workers/${workerId}/simulate-freeze?freeze=true`, { method: "POST" });
        refreshDashboard();
    };

    window.reviveWorker = async (workerId) => {
        await fetch(`/api/workers/${workerId}/revive`, { method: "POST" });
        refreshDashboard();
    };

    window.cancelJob = async (jobId) => {
        await fetch(`/api/jobs/${jobId}/cancel`, { method: "POST" });
        refreshDashboard();
    };

    window.viewJobDetails = (jobId) => {
        const job = currentJobs.find(j => j.id === jobId);
        if (job) {
            document.getElementById("detailsModalTitle").textContent = job.name;
            detailsModalBody.textContent = JSON.stringify(job, null, 2);
            detailsModal.style.display = "flex";
        }
    };

    // Initial load & Polling loop (every 800ms)
    refreshDashboard();
    setInterval(refreshDashboard, 800);
});
