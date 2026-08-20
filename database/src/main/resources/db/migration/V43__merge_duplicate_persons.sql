-- One artist, one person row. Scanning used to key persons on the exact name, so "ABBA"/"Abba" and
-- "Alice DeeJay"/"ALICE DEEJAY" each became their own artist page, and a track credited to
-- "X feat. Y" created a third artist that neither X nor Y could see. This migration rewrites the
-- featured names to their primary artist, merges the duplicates that produces, and locks identity
-- down with a unique index on the normalized name (V42's generated column).
--
-- Play history and ratings survive the merge: the collision rules below keep the row with the most
-- progress and the highest rating rather than dropping one of them.

-- 1. The old identity was the exact name, so drop that constraint first: renaming
--    "X feat. Y" to "X" would otherwise collide with the existing "X" instead of merging with it.
--    The constraint is named after the table's original name and differs between databases created
--    by Flyway and by Hibernate, so look it up rather than assume.
DO $$
DECLARE constraint_name text;
BEGIN
    SELECT con.conname INTO constraint_name
    FROM pg_constraint con
    JOIN pg_class rel ON rel.oid = con.conrelid
    WHERE rel.relname = 'person_entity' AND con.contype = 'u'
      AND (SELECT array_agg(att.attname::text ORDER BY att.attname::text)
           FROM unnest(con.conkey) AS k(attnum)
           JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = k.attnum)
          = ARRAY['library_entity_id', 'name'];
    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE person_entity DROP CONSTRAINT %I', constraint_name);
    END IF;
END $$;

-- 2. Before the name is rewritten, the guest it names is credited on the tracks of that artist —
--    that is the only place the information still exists without re-scanning every file.
CREATE TEMP TABLE feat_guest ON COMMIT DROP AS
SELECT p.id AS feat_person_id,
       p.library_entity_id,
       btrim(guest) AS guest_name,
       row_number() OVER (PARTITION BY p.id ORDER BY ordinality) AS position
FROM person_entity p
CROSS JOIN LATERAL regexp_split_to_table(
        regexp_replace(p.name, '^.*?\y(?:feat|ft|featuring)\y\.?\s+', '', 'i'),
        '\s*(?:,|&|/|\+|\yand\y)\s*') WITH ORDINALITY AS g(guest, ordinality)
WHERE p.library_entity_id IS NOT NULL
  AND p.name ~* '\y(feat|ft|featuring)\y\.?\s'
  AND btrim(guest) <> ''
  AND btrim(regexp_replace(btrim(guest), '[)\]]\s*$', '')) <> '';

UPDATE feat_guest SET guest_name = btrim(regexp_replace(guest_name, '[)\]]\s*$', ''));

-- Guests that have no person row of their own yet get one, in the artist's library.
INSERT INTO person_entity (id, date_created, date_updated, library_entity_id, name)
SELECT DISTINCT ON (fg.library_entity_id, lower(btrim(regexp_replace(fg.guest_name, '\s+', ' ', 'g'))))
       gen_random_uuid(), now(), now(), fg.library_entity_id, fg.guest_name
FROM feat_guest fg
WHERE NOT EXISTS (SELECT 1 FROM person_entity existing
                  WHERE existing.library_entity_id = fg.library_entity_id
                    AND existing.name_normalized = lower(btrim(regexp_replace(fg.guest_name, '\s+', ' ', 'g'))));

INSERT INTO track_credit_entity (id, date_created, date_updated, track_entity_id, person_entity_id, credit_type, position)
SELECT DISTINCT ON (t.id, guest.id)
       gen_random_uuid(), now(), now(), t.id, guest.id, 'FEATURED', fg.position
FROM feat_guest fg
JOIN track_entity t ON t.person_entity_id = fg.feat_person_id
JOIN person_entity guest ON guest.library_entity_id = fg.library_entity_id
                        AND guest.name_normalized = lower(btrim(regexp_replace(fg.guest_name, '\s+', ' ', 'g')))
WHERE guest.id <> fg.feat_person_id
ON CONFLICT (track_entity_id, person_entity_id) DO NOTHING;

-- 2. "Mark Mancina feat. Phil Collins" becomes "Mark Mancina", and merges into the existing
--    "Mark Mancina" in the next step. The featured guest is not recovered here — the credits of a
--    re-scanned file are; the person row alone does not say which track the guest belonged to.
UPDATE person_entity
SET name = btrim(regexp_replace(name, '\s*[(\[]?\s*\y(feat|ft|featuring)\y\.?\s+.*$', '', 'i'))
WHERE name ~* '\y(feat|ft|featuring)\y\.?\s'
  AND btrim(regexp_replace(name, '\s*[(\[]?\s*\y(feat|ft|featuring)\y\.?\s+.*$', '', 'i')) <> '';

-- 4. Per (library, normalized name), pick the row to keep: the TMDB-linked one, else the one that
--    owns the most albums and tracks, else the mixed-case spelling, else the oldest.
CREATE TEMP TABLE person_merge ON COMMIT DROP AS
WITH ranked AS (
    SELECT p.id,
           first_value(p.id) OVER (
               PARTITION BY p.library_entity_id, p.name_normalized
               ORDER BY (p.tmdb_id IS NULL),
                        (SELECT count(*) FROM album_entity a WHERE a.person_entity_id = p.id) DESC,
                        (SELECT count(*) FROM track_entity t WHERE t.person_entity_id = p.id) DESC,
                        -- An ALL-CAPS tag is a shoutier display name than the same name in mixed case.
                        (p.name = upper(p.name)),
                        p.date_created, p.id) AS keep_id
    FROM person_entity p
    -- Only persons inside a library (music artists, book authors). Library-less TMDB cast members
    -- are deliberately left alone: two actors really can share a name, and their dedup is on name
    -- plus birth year, not on the name alone.
    WHERE p.library_entity_id IS NOT NULL
)
SELECT id AS loser_id, keep_id FROM ranked WHERE id <> keep_id;

-- 5. Collisions first, or the FK repointing below would trip a unique constraint.

-- 5a. Two spellings of one artist owning the same album: fold the loser album into the keeper's.
CREATE TEMP TABLE album_merge ON COMMIT DROP AS
SELECT loser.id AS loser_id, keeper.id AS keep_id
FROM album_entity loser
JOIN person_merge m ON m.loser_id = loser.person_entity_id
JOIN album_entity keeper ON keeper.person_entity_id = m.keep_id
                        AND keeper.name = loser.name
                        AND keeper.release_year = loser.release_year
                        AND keeper.id <> loser.id;

-- 5b. Tracks that would collide on (album, number, disc) inside a merged album: keep one row and
--     move everything that points at the loser track to it.
CREATE TEMP TABLE track_merge ON COMMIT DROP AS
SELECT loser.id AS loser_id, keeper.id AS keep_id
FROM track_entity loser
JOIN album_merge am ON am.loser_id = loser.album_entity_id
JOIN track_entity keeper ON keeper.album_entity_id = am.keep_id
                        AND keeper.number = loser.number
                        AND keeper.disc_number = loser.disc_number;

UPDATE media_file_entity f SET track_entity_id = t.keep_id FROM track_merge t WHERE f.track_entity_id = t.loser_id;
UPDATE metadata_entity md SET track_entity_id = t.keep_id FROM track_merge t WHERE md.track_entity_id = t.loser_id;
UPDATE playlist_item_entity pi SET track_entity_id = t.keep_id FROM track_merge t WHERE pi.track_entity_id = t.loser_id;
UPDATE play_queue_item_entity pq SET track_entity_id = t.keep_id FROM track_merge t WHERE pq.track_entity_id = t.loser_id;
UPDATE track_credit_entity tc SET track_entity_id = t.keep_id FROM track_merge t
WHERE tc.track_entity_id = t.loser_id
  AND NOT EXISTS (SELECT 1 FROM track_credit_entity keep
                  WHERE keep.track_entity_id = t.keep_id AND keep.person_entity_id = tc.person_entity_id);
DELETE FROM track_credit_entity tc USING track_merge t WHERE tc.track_entity_id = t.loser_id;

-- Track watch rows are one per play (the unique key includes play_queue_item_id), so every play of
-- the loser track simply becomes a play of the keeper; the play history is preserved as it is.
UPDATE watch_status_entity ws SET track_entity_id = t.keep_id FROM track_merge t
WHERE ws.track_entity_id = t.loser_id
  AND NOT EXISTS (SELECT 1 FROM watch_status_entity keep
                  WHERE keep.play_queue_item_id = ws.play_queue_item_id
                    AND keep.user_entity_id = ws.user_entity_id
                    AND keep.track_entity_id = t.keep_id);
DELETE FROM watch_status_entity ws USING track_merge t WHERE ws.track_entity_id = t.loser_id;

-- One rating per user per track: the higher of the two survives.
UPDATE rating_entity keep
SET value = GREATEST(keep.value, loser.value)
FROM rating_entity loser
JOIN track_merge t ON t.loser_id = loser.track_entity_id
WHERE keep.track_entity_id = t.keep_id AND keep.user_entity_id = loser.user_entity_id;
UPDATE rating_entity r SET track_entity_id = t.keep_id FROM track_merge t
WHERE r.track_entity_id = t.loser_id
  AND NOT EXISTS (SELECT 1 FROM rating_entity keep
                  WHERE keep.track_entity_id = t.keep_id AND keep.user_entity_id = r.user_entity_id);
DELETE FROM rating_entity r USING track_merge t WHERE r.track_entity_id = t.loser_id;
DELETE FROM track_entity t USING track_merge tm WHERE t.id = tm.loser_id;

-- What is left of the loser album moves over, then the album itself goes.
UPDATE track_entity t SET album_entity_id = am.keep_id FROM album_merge am WHERE t.album_entity_id = am.loser_id;
UPDATE image_entity i SET album_entity_id = am.keep_id FROM album_merge am WHERE i.album_entity_id = am.loser_id;
UPDATE metadata_entity md SET album_entity_id = am.keep_id FROM album_merge am WHERE md.album_entity_id = am.loser_id;
UPDATE rating_entity keep
SET value = GREATEST(keep.value, loser.value)
FROM rating_entity loser
JOIN album_merge am ON am.loser_id = loser.album_entity_id
WHERE keep.album_entity_id = am.keep_id AND keep.user_entity_id = loser.user_entity_id;
UPDATE rating_entity r SET album_entity_id = am.keep_id FROM album_merge am
WHERE r.album_entity_id = am.loser_id
  AND NOT EXISTS (SELECT 1 FROM rating_entity keep
                  WHERE keep.album_entity_id = am.keep_id AND keep.user_entity_id = r.user_entity_id);
DELETE FROM rating_entity r USING album_merge am WHERE r.album_entity_id = am.loser_id;
DELETE FROM album_entity a USING album_merge am WHERE a.id = am.loser_id;

-- 5c. Books and series carry a unique key on (person, name, …) too: drop the loser rows that would
--     collide rather than fail the migration; the scanner recreates them on the next scan.
DELETE FROM book_entity loser
USING person_merge m, book_entity keeper
WHERE loser.person_entity_id = m.loser_id
  AND keeper.person_entity_id = m.keep_id
  AND keeper.name = loser.name
  AND keeper.path_year IS NOT DISTINCT FROM loser.path_year
  AND NOT EXISTS (SELECT 1 FROM media_file_entity f WHERE f.book_entity_id = loser.id)
  AND NOT EXISTS (SELECT 1 FROM chapter_entity c WHERE c.book_entity_id = loser.id);
DELETE FROM series_entity loser
USING person_merge m, series_entity keeper
WHERE loser.person_entity_id = m.loser_id
  AND keeper.person_entity_id = m.keep_id
  AND keeper.name = loser.name
  AND NOT EXISTS (SELECT 1 FROM book_entity b WHERE b.series_entity_id = loser.id);

-- 6. Repoint every person FK at the surviving row.
UPDATE track_credit_entity tc SET person_entity_id = m.keep_id FROM person_merge m
WHERE tc.person_entity_id = m.loser_id
  AND NOT EXISTS (SELECT 1 FROM track_credit_entity keep
                  WHERE keep.track_entity_id = tc.track_entity_id AND keep.person_entity_id = m.keep_id);
DELETE FROM track_credit_entity tc USING person_merge m WHERE tc.person_entity_id = m.loser_id;
UPDATE track_entity t SET person_entity_id = m.keep_id FROM person_merge m WHERE t.person_entity_id = m.loser_id;
UPDATE album_entity a SET person_entity_id = m.keep_id FROM person_merge m WHERE a.person_entity_id = m.loser_id;
UPDATE image_entity i SET person_entity_id = m.keep_id FROM person_merge m WHERE i.person_entity_id = m.loser_id;
UPDATE credit_entity c SET person_entity_id = m.keep_id FROM person_merge m WHERE c.person_entity_id = m.loser_id;
UPDATE book_entity b SET person_entity_id = m.keep_id FROM person_merge m WHERE b.person_entity_id = m.loser_id;
UPDATE series_entity s SET person_entity_id = m.keep_id FROM person_merge m WHERE s.person_entity_id = m.loser_id;
-- Bios are per language: the keeper's own bio wins, the loser's fills a language it lacks.
UPDATE metadata_entity md SET person_entity_id = m.keep_id FROM person_merge m
WHERE md.person_entity_id = m.loser_id
  AND NOT EXISTS (SELECT 1 FROM metadata_entity keep
                  WHERE keep.person_entity_id = m.keep_id
                    AND keep.language IS NOT DISTINCT FROM md.language);
DELETE FROM metadata_entity md USING person_merge m WHERE md.person_entity_id = m.loser_id;

-- 7. What only a loser knew moves to the keeper, then the duplicates are unreferenced.
UPDATE person_entity keep
SET birth_year = donor.birth_year
FROM (SELECT DISTINCT ON (m.keep_id) m.keep_id, loser.birth_year
      FROM person_merge m JOIN person_entity loser ON loser.id = m.loser_id
      WHERE loser.birth_year IS NOT NULL
      ORDER BY m.keep_id, loser.date_created) donor
WHERE keep.id = donor.keep_id AND keep.birth_year IS NULL;

UPDATE person_entity keep
SET tmdb_id = donor.tmdb_id
FROM (SELECT DISTINCT ON (m.keep_id) m.keep_id, loser.tmdb_id
      FROM person_merge m JOIN person_entity loser ON loser.id = m.loser_id
      WHERE loser.tmdb_id IS NOT NULL
      ORDER BY m.keep_id, loser.date_created) donor
WHERE keep.id = donor.keep_id AND keep.tmdb_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM person_entity other WHERE other.tmdb_id = donor.tmdb_id AND other.id <> keep.id
                  AND other.id NOT IN (SELECT loser_id FROM person_merge));

DELETE FROM person_entity p USING person_merge m WHERE p.id = m.loser_id;

-- 8. From here on the database enforces the identity the scanner already assumes.
CREATE UNIQUE INDEX person_entity_library_name_normalized_uq
    ON person_entity (library_entity_id, name_normalized);

ANALYZE person_entity, album_entity, track_entity, track_credit_entity, metadata_entity, watch_status_entity, rating_entity;
