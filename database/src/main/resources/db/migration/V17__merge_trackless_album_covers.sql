-- Albums created before their name and year came from the path (rather than from the audio tags)
-- carry a name no scanner can derive again, so the cover scanner did not recognise them and created
-- a fresh, track-less album per cover.jpg. Move those covers to the album whose tracks live in the
-- same directory, then drop the leftovers.

WITH trackless AS MATERIALIZED (
    SELECT a.id
    FROM album_entity a
    WHERE NOT EXISTS (SELECT 1 FROM track_entity t WHERE t.album_entity_id = a.id)
),
ghost_cover AS MATERIALIZED (
    SELECT i.id AS image_id, regexp_replace(i.path, '/[^/]+$', '') AS directory
    FROM image_entity i
    JOIN trackless g ON g.id = i.album_entity_id
),
album_directory AS MATERIALIZED (
    SELECT regexp_replace(m.path, '/[^/]+$', '') AS directory,
           t.album_entity_id,
           count(*) AS files
    FROM media_file_entity m
    JOIN track_entity t ON t.id = m.track_entity_id
    GROUP BY 1, 2
),
-- A directory whose files belong to several albums is a compilation split over albums; the album
-- holding most of them owns the cover.
move AS (
    SELECT DISTINCT ON (c.image_id) c.image_id, d.album_entity_id
    FROM ghost_cover c
    JOIN album_directory d ON d.directory = c.directory
    ORDER BY c.image_id, d.files DESC
)
UPDATE image_entity i
SET album_entity_id = move.album_entity_id
FROM move
WHERE i.id = move.image_id;

-- What still hangs on a track-less album has no album to move to: covers fetched from MusicBrainz
-- into the cache directory, whose files the daily cache cleanup reclaims.
DELETE FROM image_entity i
USING album_entity a
WHERE a.id = i.album_entity_id
  AND NOT EXISTS (SELECT 1 FROM track_entity t WHERE t.album_entity_id = a.id);

DELETE FROM metadata_entity m
USING album_entity a
WHERE a.id = m.album_entity_id
  AND NOT EXISTS (SELECT 1 FROM track_entity t WHERE t.album_entity_id = a.id);

DELETE FROM rating_entity r
USING album_entity a
WHERE a.id = r.album_entity_id
  AND NOT EXISTS (SELECT 1 FROM track_entity t WHERE t.album_entity_id = a.id);

DELETE FROM album_entity a
WHERE NOT EXISTS (SELECT 1 FROM track_entity t WHERE t.album_entity_id = a.id);
