#!/usr/bin/env bash
# Starts the coordinator and four demo-paced workers in the background (Linux/macOS/Git Bash).
# Run from the repository root after: ./mvnw install -DskipTests
set -euo pipefail
COORDINATOR=queuepilot-coordinator/target/queuepilot-coordinator-1.0.0.jar
WORKER=queuepilot-worker/target/queuepilot-worker-1.0.0-exec.jar
[ -f "$COORDINATOR" ] && [ -f "$WORKER" ] || { echo "Build first: ./mvnw install -DskipTests"; exit 1; }
mkdir -p data/logs data/plugins-worker-1

java -jar "$COORDINATOR" > data/logs/coordinator.log 2>&1 &
echo "Coordinator PID $!; waiting for http://localhost:8080 ..."
for _ in $(seq 1 90); do curl -sf http://localhost:8080/api/health > /dev/null && break; sleep 1; done

cp queuepilot-example-plugin/target/queuepilot-example-plugin-1.0.0.jar data/plugins-worker-1/
java -jar "$WORKER" --name worker-1 --slots 2 --pace-ms 150 --plugins data/plugins-worker-1 > data/logs/worker-1.log 2>&1 &
for n in 2 3 4; do
  java -jar "$WORKER" --name "worker-$n" --slots 2 --pace-ms 150 > "data/logs/worker-$n.log" 2>&1 &
done

echo "Dashboard: http://localhost:8080   Logs: data/logs/"
echo "Stop everything with: pkill -f queuepilot-"
