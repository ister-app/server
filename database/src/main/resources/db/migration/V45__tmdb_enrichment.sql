-- Per-language TMDB tagline on the metadata rows.
ALTER TABLE metadata_entity ADD COLUMN IF NOT EXISTS tagline text;

-- Language-independent TMDB enrichment on the container entities.
ALTER TABLE movie_entity
    ADD COLUMN IF NOT EXISTS tmdb_id integer,
    ADD COLUMN IF NOT EXISTS imdb_id varchar(16),
    ADD COLUMN IF NOT EXISTS vote_average numeric(3, 1),
    ADD COLUMN IF NOT EXISTS vote_count integer,
    ADD COLUMN IF NOT EXISTS runtime integer,
    ADD COLUMN IF NOT EXISTS content_rating varchar(16),
    ADD COLUMN IF NOT EXISTS status varchar(32),
    ADD COLUMN IF NOT EXISTS homepage text,
    ADD COLUMN IF NOT EXISTS collection_tmdb_id integer,
    ADD COLUMN IF NOT EXISTS collection_name varchar(255),
    ADD COLUMN IF NOT EXISTS studios text,
    ADD COLUMN IF NOT EXISTS origin_country varchar(64),
    ADD COLUMN IF NOT EXISTS keywords text,
    ADD COLUMN IF NOT EXISTS trailer_key varchar(32),
    ADD COLUMN IF NOT EXISTS trailer_site varchar(16);

ALTER TABLE show_entity
    ADD COLUMN IF NOT EXISTS tmdb_id integer,
    ADD COLUMN IF NOT EXISTS imdb_id varchar(16),
    ADD COLUMN IF NOT EXISTS vote_average numeric(3, 1),
    ADD COLUMN IF NOT EXISTS vote_count integer,
    ADD COLUMN IF NOT EXISTS content_rating varchar(16),
    ADD COLUMN IF NOT EXISTS status varchar(32),
    ADD COLUMN IF NOT EXISTS homepage text,
    ADD COLUMN IF NOT EXISTS networks text,
    ADD COLUMN IF NOT EXISTS studios text,
    ADD COLUMN IF NOT EXISTS origin_country varchar(64),
    ADD COLUMN IF NOT EXISTS keywords text,
    ADD COLUMN IF NOT EXISTS trailer_key varchar(32),
    ADD COLUMN IF NOT EXISTS trailer_site varchar(16);

ALTER TABLE episode_entity
    ADD COLUMN IF NOT EXISTS runtime integer,
    ADD COLUMN IF NOT EXISTS vote_average numeric(3, 1),
    ADD COLUMN IF NOT EXISTS vote_count integer;
