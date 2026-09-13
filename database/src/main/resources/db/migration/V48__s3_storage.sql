-- S3 object storage: a directory is either LOCAL (a path on exactly one owning node, as before)
-- or S3 (a bucket prefix that any number of nodes attach to). S3 directories have no owner, so
-- node_entity_id becomes nullable; the nodes that can serve a directory live in directory_node.
ALTER TABLE directory_entity
    ADD COLUMN storage_kind  VARCHAR(16) NOT NULL DEFAULT 'LOCAL',
    ADD COLUMN s3_connection VARCHAR(255),
    ADD COLUMN s3_bucket     VARCHAR(255),
    ADD COLUMN s3_prefix     VARCHAR(1024),
    ALTER COLUMN node_entity_id DROP NOT NULL;

ALTER TABLE directory_entity ADD CONSTRAINT directory_storage_shape CHECK (
    (storage_kind = 'LOCAL' AND node_entity_id IS NOT NULL) OR
    (storage_kind = 'S3' AND s3_connection IS NOT NULL AND s3_bucket IS NOT NULL));

CREATE TABLE directory_node (
    directory_entity_id UUID        NOT NULL REFERENCES directory_entity (id) ON DELETE CASCADE,
    node_entity_id      UUID        NOT NULL REFERENCES node_entity (id) ON DELETE CASCADE,
    PRIMARY KEY (directory_entity_id, node_entity_id)
);

-- Every existing (LOCAL) directory is attached to its owner.
INSERT INTO directory_node (directory_entity_id, node_entity_id)
SELECT id, node_entity_id FROM directory_entity WHERE node_entity_id IS NOT NULL;
