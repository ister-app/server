-- Subtitles in the language you are already hearing add nothing, but the two preference lists are
-- applied independently, so a user with Dutch on top of both gets Dutch audio *and* Dutch subs.
-- Opt-in per user; off keeps the old behaviour.
ALTER TABLE user_settings
    ADD COLUMN IF NOT EXISTS hide_subtitles_matching_audio BOOLEAN NOT NULL DEFAULT FALSE;
