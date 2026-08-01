-- ARTIST play queues: which ranked track list of the artist the queue plays.
ALTER TABLE play_queue_entity
    ADD COLUMN IF NOT EXISTS rank_kind VARCHAR(255);
