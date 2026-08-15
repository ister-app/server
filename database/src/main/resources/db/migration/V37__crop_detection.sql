-- Baked-in black-bar (letterbox/pillarbox) crop rectangle of a video stream,
-- detected once per file with ffmpeg cropdetect during analysis.
--
-- All four NULL = detection never ran (the scanner's backfill re-requests
-- analysis for such rows). A stored rect equal to the full frame
-- (0, 0, width, height) = detected, no bars — so the backfill never re-fires.
ALTER TABLE media_file_stream_entity
    ADD COLUMN IF NOT EXISTS crop_x      INT,
    ADD COLUMN IF NOT EXISTS crop_y      INT,
    ADD COLUMN IF NOT EXISTS crop_width  INT,
    ADD COLUMN IF NOT EXISTS crop_height INT;
