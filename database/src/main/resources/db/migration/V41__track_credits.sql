-- Track credits: a track has one primary artist (track_entity.person_entity_id) and may credit
-- featured guests. The join table is what makes "every track of this artist" include guest spots
-- and compilation appearances.
CREATE TABLE track_credit_entity (
    id               UUID NOT NULL PRIMARY KEY,
    date_created     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    track_entity_id  UUID NOT NULL REFERENCES track_entity (id) ON DELETE CASCADE,
    person_entity_id UUID NOT NULL REFERENCES person_entity (id),
    credit_type      VARCHAR(32) NOT NULL,
    position         INTEGER NOT NULL DEFAULT 0,
    UNIQUE (track_entity_id, person_entity_id)
);
CREATE INDEX ix_track_credit_person ON track_credit_entity (person_entity_id);
CREATE INDEX ix_track_credit_track ON track_credit_entity (track_entity_id);

-- The artist queries lead with these columns and none of them was indexed.
CREATE INDEX IF NOT EXISTS ix_track_entity_person ON track_entity (person_entity_id);
CREATE INDEX IF NOT EXISTS ix_track_entity_album ON track_entity (album_entity_id);
CREATE INDEX IF NOT EXISTS ix_album_entity_person ON album_entity (person_entity_id);
CREATE INDEX IF NOT EXISTS ix_album_entity_library ON album_entity (library_entity_id);

-- Backfill: every existing track credits its current artist as the primary one.
INSERT INTO track_credit_entity (id, date_created, date_updated, track_entity_id, person_entity_id, credit_type, position)
SELECT gen_random_uuid(), now(), now(), t.id, t.person_entity_id, 'PRIMARY', 0
FROM track_entity t
ON CONFLICT DO NOTHING;

ANALYZE track_credit_entity, track_entity, album_entity;
