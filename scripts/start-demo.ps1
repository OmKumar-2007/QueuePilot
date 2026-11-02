# Starts the coordinator and two workers in separate windows (Windows PowerShell).
# Run from the repository root after: .\mvnw.cmd install -DskipTests
$ErrorActionPreference = "Stop"
$coordinator = "queuepilot-coordinator/target/queuepilot-coordinator-1.0.0.jar"
$worker = "queuepilot-worker/target/queuepilot-worker-1.0.0.jar"
if (-not (Test-Path $coordinator) -or -not (Test-Path $worker)) {
    Write-Error "Build first: .\mvnw.cmd install -DskipTests"
}

Start-Process java -ArgumentList "-jar", $coordinator
Write-Host "Waiting for the coordinator on http://localhost:8080 ..."
for ($i = 0; $i -lt 90; $i++) {
    try { Invoke-RestMethod http://localhost:8080/api/health | Out-Null; break } catch { Start-Sleep -Seconds 1 }
}

New-Item -ItemType Directory -Force data/plugins-worker-1 | Out-Null
Copy-Item queuepilot-example-plugin/target/queuepilot-example-plugin-1.0.0.jar data/plugins-worker-1/ -Force
Start-Process java -ArgumentList "-jar", $worker, "--name", "worker-1", "--slots", "2", "--plugins", "data/plugins-worker-1"
Start-Process java -ArgumentList "-jar", $worker, "--name", "worker-2", "--slots", "2"

Write-Host "Dashboard: http://localhost:8080   Admin password: data/admin-password.txt"
Start-Process "http://localhost:8080"
