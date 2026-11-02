# QueuePilot: Task Execution, Scheduling & Worker Orchestration Platform

QueuePilot is a resilient, multi-threaded task orchestration platform developed in Java and Spring Boot for the **CS1207 Object Oriented Programming Lab**. 

It accepts heterogeneous computational workloads (numerical sorting, text NLP tokenization, and mathematical simulations), schedules them using configurable Operating Systems policies (**FCFS**, **SJF**, **Priority with Aging**, and **MLFQ**), and executes them concurrently across a pool of 4 worker threads with continuous heartbeat leases and automatic failure recovery.

---

## 🌟 Key Architecture & OOP Highlights

- **Abstraction & Interfaces:** [`JobExecutor`](src/main/java/com/queuepilot/executor/JobExecutor.java), [`SchedulingPolicy`](src/main/java/com/queuepilot/scheduler/SchedulingPolicy.java), and [`WorkerEventListener`](src/main/java/com/queuepilot/worker/WorkerEventListener.java).
- **Inheritance & Polymorphism:** Abstract base [`Job`](src/main/java/com/queuepilot/model/Job.java) specialized into [`SortingJob`](src/main/java/com/queuepilot/model/SortingJob.java), [`TextAnalysisJob`](src/main/java/com/queuepilot/model/TextAnalysisJob.java), and [`MathComputeJob`](src/main/java/com/queuepilot/model/MathComputeJob.java), executed polymorphically.
- **Multithreading & Concurrency:** Dedicated 4-worker thread pool ([`WorkerPool`](src/main/java/com/queuepilot/worker/WorkerPool.java)), thread synchronization using `ReentrantLock`, blocking queues, and non-blocking event-driven dispatching ([`DispatchCoordinator`](src/main/java/com/queuepilot/dispatch/DispatchCoordinator.java)).
- **DAG Dependency & Cycle Detection:** Depth-First Search with 3-color marking in [`DependencyGraph`](src/main/java/com/queuepilot/dag/DependencyGraph.java) rejects cyclic dependencies and unblocks downstream tasks upon prerequisite completion.
- **Lease Fencing & Fault Recovery:** Time-limited leases, attempt tokens, and background [`RecoveryWatchdog`](src/main/java/com/queuepilot/recovery/RecoveryWatchdog.java) that detects dead/unresponsive workers and reassigns abandoned work.
- **Long-Running Demonstrable Jobs:** Built-in jobs run for **10 to 30 seconds** in stepped iterations, emitting periodic progress updates (0% to 100%) and continuous heartbeats for live demonstration.
- **Persistence & Auditing:** Spring Data JPA with H2/PostgreSQL storing job histories, attempt logs, worker metrics, and system audit events ([`AuditService`](src/main/java/com/queuepilot/persistence/AuditService.java)).

---

## 🚀 Running the Application

### Prerequisites
- Java 21+ (or OpenJDK 21/27)
- Maven 3.9+ (pre-configured)

### Run from Packaged JAR:
```bash
java -jar target/queuepilot-1.0.0.jar
```

### Run using Maven:
```bash
mvn spring-boot:run
```

Once started, open your web browser at:
👉 **`http://localhost:8080`**

---

## 🎯 Professor Demonstration Script (7-Step Demo Scenario)

Follow these exact steps during your lab presentation:

### Step 1: Submit 10 Jobs
- Click **"🚀 Seed 10 Demo Jobs (10-30s)"** on the top navigation bar.
- *What happens:* QueuePilot injects 10 varied jobs (numerical sorting with QuickSort/MergeSort, text NLP corpus analysis, and Monte Carlo Pi simulations) designed to run for 10 to 30 seconds each. Notice Job 10 is marked `BLOCKED` because it depends on Job 1 completing first (DAG dependency).

### Step 2: Select a Scheduling Policy
- In the top dropdown, select between:
  - **FCFS** (First Come, First Served)
  - **SJF** (Shortest Job First)
  - **Priority with Aging** (prevents starvation)
  - **MLFQ** (Multi-Level Feedback Queue with 3 tiers and periodic boost)
- *What happens:* The active policy re-evaluates the queue order dynamically.

### Step 3: Execute Jobs Concurrently
- Observe the **Concurrent Worker Pool** section showing **4 distinct workers** executing different jobs simultaneously:
  - Worker 1: Generalist
  - Worker 2: Sorting Specialist
  - Worker 3: Text Analysis Specialist
  - Worker 4: Math Computation Specialist

### Step 4: Monitor Execution
- Watch live progress bars (0% → 100%) in real-time as workers compute in stepped iterations.
- Notice live worker statuses (`BUSY`), heartbeat activity, elapsed durations, and task descriptions.
- When Job 1 completes, notice Job 10 automatically unblocks from `BLOCKED` to `QUEUED` and is dispatched.

### Step 5: Simulate a Worker Failure
- While a worker is actively executing a task (e.g., Worker 2), click the red **"⚡ Crash Worker"** button on its card.
- *What happens:* Worker 2 transitions to `TERMINATED / DEAD` and its execution thread is killed mid-task.

### Step 6: Demonstrate Recovery
- Within moments, the **Recovery Watchdog** detects the crashed worker and expired lease.
- Switch to the **"Live Event Audit Log"** tab to see:
  1. `WORKER_FAILURE_DETECTED`: "Worker lease expired or failed: Worker crash"
  2. `JOB_REASSIGNED`: "Job re-queued for retry 1/3"
- Notice the abandoned job is placed back into the queue and automatically claimed by another healthy worker (e.g. Worker 1), which finishes the job successfully!
- Click **"💚 Revive Worker"** on Worker 2 to bring it back to `IDLE`.

### Step 7: Compare Scheduling Algorithms
- Click the **"Algorithm Benchmark (Step 7)"** tab.
- Click **"📊 Run Comparative Benchmark"**.
- *What happens:* The engine runs identical 10-job synthetic workloads through FCFS, SJF, Priority Aging, and MLFQ, generating visual bar charts and comparative metrics:
  - **Average Turnaround Time ($T_a$)**
  - **Average Waiting Time ($T_w$)**
  - **Throughput (jobs/second)**
  - **Worker Utilization (%)**

---

## 📡 REST API Reference

| Endpoint | Method | Description |
| :--- | :--- | :--- |
| `/api/jobs` | `POST` | Submit a new task with priority, duration (10–30s), and dependencies |
| `/api/jobs` | `GET` | List all jobs (filter by `?state=...`) |
| `/api/jobs/{id}` | `GET` | Get detailed job status, attempt history, and outputs |
| `/api/jobs/{id}/cancel` | `POST` | Cancel a job |
| `/api/jobs` | `DELETE` | Clear queue |
| `/api/workers` | `GET` | Get real-time health and assignments of all 4 workers |
| `/api/workers/{id}/simulate-crash` | `POST` | Simulates physical thread crash |
| `/api/workers/{id}/simulate-freeze` | `POST` | Freezes heartbeats to test lease expiry |
| `/api/workers/{id}/revive` | `POST` | Revives a dead worker |
| `/api/scheduler/policy` | `GET`/`POST`| Query or switch policy (`FCFS`, `SJF`, `PRIORITY_AGING`, `MLFQ`) |
| `/api/events` | `GET` | Stream recent system audit events |
| `/api/demo/seed` | `POST` | Ingests the 10 demo tasks (10–30s) |
| `/api/benchmarks/run` | `POST` | Evaluates all 4 policies on identical workload |

---

## 🧪 Automated Test Suite

Run the full test suite with:
```bash
mvn test
```
All **26 unit and integration test cases** pass with zero errors, validating scheduling fairness, priority aging, MLFQ tiering/boost, DAG cycle detection, multi-worker concurrency, and watchdog lease recovery.
