-- COMIC libraries: comic volumes reuse book_entity, comic series reuse series_entity.
-- A comic has no author from the path, so person becomes nullable; comic identity is
-- scoped by the series instead (partial unique indexes on person IS NULL rows), which
-- keeps the existing person-keyed unique constraints guarding real books.

ALTER TABLE book_entity   ALTER COLUMN person_entity_id DROP NOT NULL;
ALTER TABLE series_entity ALTER COLUMN person_entity_id DROP NOT NULL;

-- Series start year from the "(YYYY)" suffix on the series directory (0 = none).
ALTER TABLE series_entity ADD COLUMN IF NOT EXISTS start_year INTEGER NOT NULL DEFAULT 0;

-- Page count of a cbz (number of image entries) or pdf (PDDocument.getNumberOfPages()).
ALTER TABLE media_file_entity ADD COLUMN IF NOT EXISTS page_count INTEGER;

-- Series-level metadata (Wikipedia description) and artwork (folder.jpg / wiki thumbnail).
ALTER TABLE metadata_entity ADD COLUMN IF NOT EXISTS series_entity_id UUID REFERENCES series_entity(id);
ALTER TABLE image_entity    ADD COLUMN IF NOT EXISTS series_entity_id UUID REFERENCES series_entity(id);
CREATE INDEX IF NOT EXISTS metadata_entity_series_idx ON metadata_entity(series_entity_id);
CREATE INDEX IF NOT EXISTS image_entity_series_idx    ON image_entity(series_entity_id);

-- Comic identity: two same-named volumes ("Volume 1") in different series must never collide.
CREATE UNIQUE INDEX IF NOT EXISTS book_entity_comic_identity
    ON book_entity(series_entity_id, name, path_year) WHERE person_entity_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS series_entity_comic_identity
    ON series_entity(library_entity_id, name, start_year) WHERE person_entity_id IS NULL;
