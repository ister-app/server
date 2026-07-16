-- Books: split scanner identity from display data, and add series support.
--
-- * media_file_entity.isbn: ISBN extracted from the epub OPF (dc:identifier), used for
--   Open Library matching.
-- * series_entity + book_entity.series_entity_id/series_index: a book series ("reeks"),
--   detected from epub metadata (calibre / EPUB3 belongs-to-collection) or a shared
--   "Series - Title" prefix across an author's books.
-- * book_entity.title: clean display title (name with the series prefix stripped); NULL
--   means "fall back to name".
-- * book_entity.path_year: the year from the "(YYYY)" path suffix; identity for scanner
--   matching. release_year becomes the display/sort year, recomputable with precedence
--   path > Open Library > local metadata.

ALTER TABLE media_file_entity ADD COLUMN IF NOT EXISTS isbn VARCHAR(20);

CREATE TABLE IF NOT EXISTS series_entity (
    id                UUID NOT NULL PRIMARY KEY,
    date_created      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    library_entity_id UUID NOT NULL REFERENCES library_entity(id),
    person_entity_id  UUID NOT NULL REFERENCES person_entity(id),
    name              VARCHAR(255) NOT NULL,
    UNIQUE (person_entity_id, name)
);

ALTER TABLE book_entity
    ADD COLUMN IF NOT EXISTS series_entity_id UUID REFERENCES series_entity(id),
    ADD COLUMN IF NOT EXISTS series_index     DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS title            VARCHAR(255);
CREATE INDEX IF NOT EXISTS book_entity_series_idx ON book_entity(series_entity_id);

ALTER TABLE book_entity ADD COLUMN IF NOT EXISTS path_year INTEGER NOT NULL DEFAULT 0;
UPDATE book_entity SET path_year = release_year;

-- Unlock books whose year demonstrably came from local metadata (an nfo/epub row carries the
-- same year), so external enrichment may correct release_year. Conservative: skip when another
-- same-name book of the same author exists (identity must stay unambiguous).
UPDATE book_entity b SET path_year = 0
WHERE b.release_year > 0
  AND EXISTS (SELECT 1 FROM metadata_entity m
              WHERE m.book_entity_id = b.id
                AND m.released IS NOT NULL
                AND EXTRACT(YEAR FROM m.released) = b.release_year
                AND m.source_uri LIKE 'file://%')
  AND NOT EXISTS (SELECT 1 FROM book_entity o
                  WHERE o.person_entity_id = b.person_entity_id
                    AND o.name = b.name AND o.id <> b.id);

-- Identity now keys on the path year: move the unique constraint (drop by lookup, the
-- generated name differs between Hibernate- and Flyway-created schemas).
DO $$
DECLARE con text;
BEGIN
    SELECT conname INTO con FROM pg_constraint
    WHERE conrelid = 'book_entity'::regclass AND contype = 'u';
    IF con IS NOT NULL THEN
        EXECUTE format('ALTER TABLE book_entity DROP CONSTRAINT %I', con);
    END IF;
END $$;

ALTER TABLE book_entity
    ADD CONSTRAINT book_entity_person_name_path_year_key
    UNIQUE (person_entity_id, name, path_year);
