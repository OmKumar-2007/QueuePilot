-- v2: workers can be PAUSED, several commands can wait for one heartbeat, and workers report
-- their demo pacing so the dashboard can show that a worker is deliberately slowed down.
ALTER TABLE worker ADD COLUMN demo_pace_ms BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE worker ALTER COLUMN pending_command SET DATA TYPE VARCHAR(200);
