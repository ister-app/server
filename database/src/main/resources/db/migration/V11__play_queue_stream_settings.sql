-- Last-known stream settings of the client playing this queue, reported via updatePlayQueue.
-- Used to prefetch (pre-transcode) the next queue item in the same format the user is watching.

ALTER TABLE play_queue_entity
    ADD COLUMN IF NOT EXISTS stream_direct          BOOLEAN,
    ADD COLUMN IF NOT EXISTS stream_transcode       BOOLEAN,
    ADD COLUMN IF NOT EXISTS stream_subtitle_format VARCHAR(16);
