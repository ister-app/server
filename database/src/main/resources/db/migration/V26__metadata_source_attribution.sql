-- Normalized attribution source for metadata and images, derived from the sourceUri scheme.
-- Person bios written as musicbrainz://artist/... are Wikipedia text (fetched via Wikidata),
-- so they backfill as WIKIPEDIA; the rare provider-annotation fallback is accepted as-is.
ALTER TABLE metadata_entity ADD COLUMN source varchar(32);
ALTER TABLE image_entity ADD COLUMN source varchar(32);

UPDATE metadata_entity SET source = CASE
    WHEN lower(source_uri) LIKE 'tmdb://%' THEN 'TMDB'
    WHEN lower(source_uri) LIKE 'musicbrainz://artist/%' THEN 'WIKIPEDIA'
    WHEN lower(source_uri) LIKE 'musicbrainz://%' THEN 'MUSICBRAINZ'
    WHEN lower(source_uri) LIKE 'openlibrary://author/%' THEN 'WIKIPEDIA'
    WHEN lower(source_uri) LIKE 'openlibrary://%' THEN 'OPEN_LIBRARY'
    WHEN lower(source_uri) LIKE 'wikipedia://%' THEN 'WIKIPEDIA'
    WHEN lower(source_uri) LIKE 'wikidata://%' THEN 'WIKIDATA'
    WHEN lower(source_uri) LIKE 'feed://%' THEN 'PODCAST_FEED'
    WHEN lower(source_uri) LIKE 'file://%' THEN 'LOCAL_FILE'
END
WHERE source_uri IS NOT NULL;

-- Cover Art Archive covers and Wikimedia images carry a full download URL after the scheme,
-- whose host names the actual origin; check those before the scheme.
UPDATE image_entity SET source = CASE
    WHEN lower(source_uri) LIKE '%coverartarchive.org%' THEN 'COVER_ART_ARCHIVE'
    WHEN lower(source_uri) LIKE '%wikimedia.org%' THEN 'WIKIMEDIA_COMMONS'
    WHEN lower(source_uri) LIKE 'tmdb://%' THEN 'TMDB'
    WHEN lower(source_uri) LIKE 'musicbrainz://%' THEN 'MUSICBRAINZ'
    WHEN lower(source_uri) LIKE 'openlibrary://%' THEN 'OPEN_LIBRARY'
    WHEN lower(source_uri) LIKE 'wikipedia://%' THEN 'WIKIPEDIA'
    WHEN lower(source_uri) LIKE 'file://%' THEN 'LOCAL_FILE'
END
WHERE source_uri IS NOT NULL;
