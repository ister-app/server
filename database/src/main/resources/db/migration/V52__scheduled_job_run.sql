-- Cluster-wide "has this job run recently" bookkeeping for the schedulers that fan work out for
-- the whole cluster (the nightly continue-watching rebuild, the podcast feed refresh). Every node
-- runs the same cron; the first one to move last_run_at forward does the queueing, the others see
-- a fresh timestamp and skip. One row per job name.
CREATE TABLE scheduled_job_run (
    name        VARCHAR(64) NOT NULL PRIMARY KEY,
    last_run_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
