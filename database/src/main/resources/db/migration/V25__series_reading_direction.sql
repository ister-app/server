-- Detected default reading direction of a series (RTL for manga, from ComicInfo.xml or the
-- Wikidata enrichment); NULL = no signal, which resolves to LTR.
ALTER TABLE series_entity ADD COLUMN default_reading_direction VARCHAR(8);

-- Per-user, per-series reading direction override. Stored server-side so the choice follows the
-- user across devices. No row = the series default applies.
CREATE TABLE IF NOT EXISTS user_series_preference (
    id                   UUID NOT NULL PRIMARY KEY,
    date_created         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    user_entity_id       UUID NOT NULL REFERENCES user_entity(id),
    series_entity_id     UUID NOT NULL REFERENCES series_entity(id),
    reading_direction    VARCHAR(8) NOT NULL
);

-- At most one preference row per (user, series).
CREATE UNIQUE INDEX IF NOT EXISTS user_series_preference_user_series_uq
    ON user_series_preference (user_entity_id, series_entity_id);
