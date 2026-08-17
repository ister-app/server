-- One intro/outro per (file, type, episode slice). Detection recomputes a file's segments with a
-- delete-then-insert, which is idempotent when run serially but not when two DETECT_SEGMENTS
-- messages for the same season are handled concurrently: each transaction deletes only the rows it
-- can see and then inserts its own, so the same segment ended up stored two or three times (one per
-- consumer). The detector now takes a per-season advisory lock; this index is the backstop.

-- Keep the oldest row of every duplicate group.
DELETE FROM media_file_segment_entity a
    USING media_file_segment_entity b
WHERE a.media_file_entity_id = b.media_file_entity_id
  AND a.type = b.type
  AND a.episode_entity_id IS NOT DISTINCT FROM b.episode_entity_id
  AND (a.date_created, a.id) > (b.date_created, b.id);

-- NULLS NOT DISTINCT: episode_entity_id is null for single-episode files, and two null rows of the
-- same file and type are exactly the duplicate this guards against.
CREATE UNIQUE INDEX IF NOT EXISTS media_file_segment_entity_unique_idx
    ON media_file_segment_entity (media_file_entity_id, type, episode_entity_id) NULLS NOT DISTINCT;
