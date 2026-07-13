-- Per-user playback settings. Stored server-side (not only in the client's SharedPreferences) so the
-- choice follows the user across devices, and — the reason this table exists now — so pre-transcoding
-- knows which audio tracks are worth producing. Without it, a file with seven audio streams had every
-- one of them transcoded in two bitrates while nobody would ever play five of them.
--
-- The language columns hold ordered, comma-separated tags (first match wins, as the player applies
-- them). A user without a row falls back to the app-wide app.ister.languages.
CREATE TABLE IF NOT EXISTS user_settings (
    id                           UUID NOT NULL PRIMARY KEY,
    date_created                 TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated                 TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    user_entity_id               UUID NOT NULL REFERENCES user_entity(id),
    preferred_audio_languages    VARCHAR(255) NOT NULL DEFAULT '',
    preferred_subtitle_languages VARCHAR(255) NOT NULL DEFAULT '',
    direct_play                  BOOLEAN NOT NULL DEFAULT TRUE,
    transcode                    BOOLEAN NOT NULL DEFAULT TRUE,
    -- Highest video variant to pre-transcode (720 / 480); NULL means every variant.
    max_video_height             INT
);

-- At most one settings row per user.
CREATE UNIQUE INDEX IF NOT EXISTS user_settings_user_uq ON user_settings (user_entity_id);
