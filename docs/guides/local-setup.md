# Local Setup

1. Install a JDK 21 (e.g. Temurin) and check `java -version`.
2. Clone the repository. Maven is not required: use `./mvnw` (or `mvnw.cmd` on Windows).
3. Build and test: `./mvnw verify` (first run downloads dependencies, including embedded PostgreSQL binaries for one test).
4. Start the coordinator: `java -jar queuepilot-coordinator/target/queuepilot-coordinator-1.0.0.jar`
   - Listens on `127.0.0.1:8080` only.
   - Creates `data/` (database, artifacts, generated secrets).
5. Start one or more workers: `java -jar queuepilot-worker/target/queuepilot-worker-1.0.0-exec.jar --name worker-1 --slots 2`
   - Run from the same folder so the worker finds `data/worker-secret.txt`, or set `QP_WORKER_SECRET`.
   - `--help` lists all options (capabilities, plugins folder, containers).
6. Open http://localhost:8080.

## Configuration

| Setting | How |
|---|---|
| Admin password | `QP_ADMIN_PASSWORD` (otherwise generated into `data/admin-password.txt`) |
| Worker enrollment secret | `QP_WORKER_SECRET` on coordinator and workers (otherwise `data/worker-secret.txt`) |
| PostgreSQL | `--spring.profiles.active=postgres` + `QP_DB_*` ([database setup](database-setup.md)) |
| Timings | `--queuepilot.lease-duration-ms=...`, `--queuepilot.worker-timeout-ms=...`, etc. (see `application.yml`) |
| Port | `--server.port=9090` |

## IDE

Import the root `pom.xml` as a Maven project. Run `QueuePilotCoordinatorApplication` and `WorkerMain` (program arguments `--name w1`) directly from the IDE; set the working directory to the repository root.
