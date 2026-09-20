-- Which media file of the current item the playing client opened, reported via updatePlayQueue
-- (streamSettings.mediaFileId). An item can have several files (a 4K and a 1080p version, another
-- cut); the watched boundary, the session duration, the prefetch and a device taking the queue
-- over all have to mean the same one. Null until a client reports it: the first file then.

ALTER TABLE play_queue_entity
    ADD COLUMN IF NOT EXISTS current_media_file_id UUID;
