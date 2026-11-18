-- Explicitly apply collected candidates through the existing metadata workflow.
ALTER TABLE integration.source_metadata
    ADD COLUMN dataset_id uuid REFERENCES catalog.dataset(id),
    ADD COLUMN applied_source_hash char(64),
    ADD COLUMN applied_title varchar(300),
    ADD COLUMN applied_description text,
    ADD COLUMN application_version bigint NOT NULL DEFAULT 0,
    ADD COLUMN applied_at timestamptz;
CREATE INDEX source_metadata_dataset ON integration.source_metadata(dataset_id) WHERE dataset_id IS NOT NULL;

CREATE TABLE integration.candidate_application (
    id uuid PRIMARY KEY,
    source_metadata_id uuid NOT NULL REFERENCES integration.source_metadata(id),
    source_revision_no integer NOT NULL,
    source_hash char(64) NOT NULL,
    dataset_id uuid NOT NULL REFERENCES catalog.dataset(id),
    dataset_revision_id uuid NOT NULL REFERENCES catalog.dataset_revision(id),
    dataset_lock_version bigint NOT NULL,
    actor_id uuid NOT NULL,
    request_id varchar(64) NOT NULL,
    idempotency_digest char(64) NOT NULL,
    request_hash char(64) NOT NULL,
    applied_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(source_metadata_id, actor_id, idempotency_digest)
);
CREATE TRIGGER candidate_application_append_only BEFORE UPDATE OR DELETE ON integration.candidate_application
    FOR EACH ROW EXECUTE FUNCTION integration.protect_connection_event();
