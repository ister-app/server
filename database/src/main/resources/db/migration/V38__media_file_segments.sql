-- Detected intro/outro (credits) segments of a media file, found once per season by
-- comparing audio fingerprints of sibling episodes (a shared audio run = the intro).
--
-- Timestamps are absolute file time. For a multi-episode file (s04e06-e07.mkv) each
-- contained episode gets its own rows, disambiguated by episode_entity_id; for
-- single-episode files episode_entity_id is NULL.
CREATE TABLE IF NOT EXISTS media_file_segment_entity (
    id                    UUID NOT NULL PRIMARY KEY,
    date_created          TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated          TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    media_file_entity_id  UUID NOT NULL REFERENCES media_file_entity(id) ON DELETE CASCADE,
    episode_entity_id     UUID REFERENCES episode_entity(id) ON DELETE CASCADE,
    type                  VARCHAR(16) NOT NULL,
    start_in_milliseconds BIGINT NOT NULL,
    end_in_milliseconds   BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS media_file_segment_entity_file_idx
    ON media_file_segment_entity (media_file_entity_id);

-- NULL = segment detection never ran for this file (the scanner's backfill re-requests
-- it). Set to the detector version when it ran — found or not — so seasons without a
-- detectable intro don't re-fire forever; bumping the version re-runs everything.
ALTER TABLE media_file_entity
    ADD COLUMN IF NOT EXISTS segment_detector_version INT;

-- Auto-seek past detected intros; server-side so it follows the account.
ALTER TABLE user_settings
    ADD COLUMN IF NOT EXISTS auto_skip_intro BOOLEAN NOT NULL DEFAULT FALSE;
