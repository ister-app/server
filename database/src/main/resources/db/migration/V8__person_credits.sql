ALTER TABLE person_entity ALTER COLUMN library_entity_id DROP NOT NULL;
ALTER TABLE person_entity ADD COLUMN tmdb_id BIGINT;
ALTER TABLE person_entity ADD COLUMN birth_year INTEGER;
CREATE UNIQUE INDEX ux_person_entity_tmdb_id ON person_entity (tmdb_id);

CREATE TABLE credit_entity (
    id                UUID NOT NULL PRIMARY KEY,
    date_created      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    person_entity_id  UUID NOT NULL REFERENCES person_entity (id),
    movie_entity_id   UUID REFERENCES movie_entity (id),
    show_entity_id    UUID REFERENCES show_entity (id),
    episode_entity_id UUID REFERENCES episode_entity (id),
    character_name    VARCHAR(512),
    credit_type       VARCHAR(32) NOT NULL,
    cast_order        INTEGER,
    tmdb_credit_id    VARCHAR(64)
);
CREATE INDEX ix_credit_person ON credit_entity (person_entity_id);
CREATE INDEX ix_credit_movie ON credit_entity (movie_entity_id);
CREATE INDEX ix_credit_show ON credit_entity (show_entity_id);
CREATE INDEX ix_credit_episode ON credit_entity (episode_entity_id);
