-- Person identity ignores case and repeated whitespace: "ABBA" and "Abba" are one artist.
-- The unique index over this column is created in V43, after the existing duplicates are merged.
ALTER TABLE person_entity
    ADD COLUMN name_normalized VARCHAR(255)
        GENERATED ALWAYS AS (lower(btrim(regexp_replace(name, '\s+', ' ', 'g')))) STORED;

CREATE INDEX ix_person_entity_library_name_normalized ON person_entity (library_entity_id, name_normalized);
