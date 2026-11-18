CREATE TABLE standard.definition_import (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES identity.organization(id) ON DELETE RESTRICT,
    dataset_id uuid REFERENCES catalog.dataset(id) ON DELETE RESTRICT,
    requested_by uuid NOT NULL,
    source_hash varchar(64) NOT NULL,
    source_size integer NOT NULL CHECK(source_size > 0 AND source_size <= 1048576),
    parser_version varchar(50) NOT NULL,
    status varchar(20) NOT NULL CHECK(status='VALIDATED'),
    field_count integer NOT NULL CHECK(field_count BETWEEN 1 AND 500),
    warnings jsonb NOT NULL CHECK(jsonb_typeof(warnings)='array'),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE standard.definition_field (
    id uuid PRIMARY KEY,
    import_id uuid NOT NULL REFERENCES standard.definition_import(id) ON DELETE RESTRICT,
    ordinal integer NOT NULL CHECK(ordinal>=0),
    field_definition jsonb NOT NULL CHECK(jsonb_typeof(field_definition)='object'),
    source_pointer varchar(200) NOT NULL,
    UNIQUE(import_id,ordinal),
    UNIQUE(import_id,id)
);
CREATE TABLE standard.definition_application (
    id uuid PRIMARY KEY,
    import_id uuid NOT NULL REFERENCES standard.definition_import(id) ON DELETE RESTRICT,
    dataset_id uuid NOT NULL REFERENCES catalog.dataset(id) ON DELETE RESTRICT,
    revision_id uuid NOT NULL REFERENCES catalog.dataset_revision(id) ON DELETE RESTRICT,
    requested_by uuid NOT NULL,
    idempotency_digest varchar(64) NOT NULL,
    request_hash varchar(64) NOT NULL,
    selected_field_ids jsonb NOT NULL CHECK(jsonb_typeof(selected_field_ids)='array'),
    result jsonb NOT NULL CHECK(jsonb_typeof(result)='object'),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(requested_by,idempotency_digest)
);
CREATE INDEX definition_import_org_time ON standard.definition_import(organization_id,created_at DESC,id);
CREATE TRIGGER definition_import_immutable BEFORE UPDATE OR DELETE ON standard.definition_import
    FOR EACH ROW EXECUTE FUNCTION metadata.immutable_record();
CREATE TRIGGER definition_field_immutable BEFORE UPDATE OR DELETE ON standard.definition_field
    FOR EACH ROW EXECUTE FUNCTION metadata.immutable_record();
CREATE TRIGGER definition_application_immutable BEFORE UPDATE OR DELETE ON standard.definition_application
    FOR EACH ROW EXECUTE FUNCTION metadata.immutable_record();

ALTER TABLE analytics.analysis_run ALTER COLUMN dataset_revision_id DROP NOT NULL;
ALTER TABLE analytics.analysis_run ADD COLUMN definition_import_id uuid REFERENCES standard.definition_import(id) ON DELETE RESTRICT;
ALTER TABLE analytics.analysis_run ADD COLUMN evidence jsonb NOT NULL DEFAULT '[]'::jsonb CHECK(jsonb_typeof(evidence)='array');
ALTER TABLE analytics.analysis_run DROP CONSTRAINT analysis_run_kind_check;
ALTER TABLE analytics.analysis_run ADD CONSTRAINT analysis_run_kind_check CHECK(kind IN ('DEFINITION_EXPORT','FIELD_MAPPING','DEFINITION_IMPORT'));
CREATE TRIGGER analysis_run_immutable BEFORE UPDATE OR DELETE ON analytics.analysis_run
    FOR EACH ROW EXECUTE FUNCTION metadata.immutable_record();
