-- Transcoded variants are opt-in: play the file as it is (direct play) unless the user asks for a
-- re-encode. Only the column default moves — every existing row holds a choice the user made, and
-- JPA always writes the column explicitly, so this keeps the schema honest rather than changing
-- anyone's playback.
ALTER TABLE user_settings ALTER COLUMN transcode SET DEFAULT FALSE;
