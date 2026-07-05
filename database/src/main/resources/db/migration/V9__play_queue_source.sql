-- Play queue source support for lazy (sliding window) materialization and shuffle.
-- source_exhausted defaults to TRUE so existing, fully materialized queues never trigger appends.

ALTER TABLE play_queue_entity
    ADD COLUMN IF NOT EXISTS source_type       VARCHAR(255),
    ADD COLUMN IF NOT EXISTS source_id         UUID,
    ADD COLUMN IF NOT EXISTS shuffle           BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS shuffle_seed      VARCHAR(64),
    ADD COLUMN IF NOT EXISTS source_offset     INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS source_exhausted  BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS source_start_id   UUID;
