-- Metadata provenance URIs do not fit in varchar(255): Wikipedia portrait images carry
-- "wikipedia://https://upload.wikimedia.org/..." source URIs that exceed it, which
-- dead-lettered the IMAGE_FOUND events for those covers. The URI is provenance/dedup
-- data with no natural upper bound, so widen it to TEXT on both carriers.

ALTER TABLE image_entity    ALTER COLUMN source_uri TYPE TEXT;
ALTER TABLE metadata_entity ALTER COLUMN source_uri TYPE TEXT;
