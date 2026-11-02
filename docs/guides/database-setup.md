# Database Setup

## Default: embedded H2 (nothing to install)

The coordinator stores its database in `data/db/` using H2 in **PostgreSQL compatibility mode**. The schema is created by the same Flyway migrations used for PostgreSQL. Data survives restarts. Delete the `data/` folder to start fresh.

## PostgreSQL

1. Install PostgreSQL 14 or newer (Windows: the EDB installer or `winget install PostgreSQL.PostgreSQL.16`).
2. Create a database and user (in `psql` as the postgres superuser):

   ```sql
   CREATE USER queuepilot WITH PASSWORD 'choose-a-password';
   CREATE DATABASE queuepilot OWNER queuepilot;
   ```

3. Start the coordinator with the `postgres` profile. Credentials come from environment variables, never from files in git:

   ```powershell
   $env:QP_DB_PASSWORD = "choose-a-password"
   java -jar queuepilot-coordinator/target/queuepilot-coordinator-1.0.0.jar --spring.profiles.active=postgres
   ```

   Optional: `QP_DB_URL` (default `jdbc:postgresql://localhost:5432/queuepilot`) and `QP_DB_USER` (default `queuepilot`).

Flyway creates the tables on first start (`db/migration/V1__init.sql`). `PostgresIntegrationTest` runs the migration and a full job lifecycle against a real PostgreSQL server in the test suite, so both databases are exercised.

## Inspecting data

Useful tables: `job`, `attempt`, `worker`, `system_event` (history), `task_definition`, `artifact`, `benchmark_report`. See [database.md](../architecture/database.md).
