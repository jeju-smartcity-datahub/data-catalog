-- Immutable bounded snapshots of authorized analytics list/statistics results.
CREATE TABLE analytics.report (
    id uuid PRIMARY KEY,
    requested_by uuid NOT NULL,
    organization_id uuid REFERENCES identity.organization(id) ON DELETE RESTRICT,
    kind varchar(10) NOT NULL CHECK (kind IN ('SEARCH','ANALYSIS')),
    format varchar(4) NOT NULL CHECK (format IN ('JSON','CSV')),
    filters jsonb NOT NULL CHECK (jsonb_typeof(filters)='object'),
    cutoff timestamptz NOT NULL,
    row_count integer NOT NULL CHECK (row_count BETWEEN 0 AND 5000),
    key_digest varchar(64) NOT NULL CHECK (key_digest ~ '^[0-9a-f]{64}$'),
    request_hash varchar(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    content_hash varchar(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    file_content bytea NOT NULL CHECK (octet_length(file_content) BETWEEN 1 AND 8388608),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at timestamptz NOT NULL DEFAULT (CURRENT_TIMESTAMP + interval '24 hours'),
    UNIQUE (requested_by,key_digest),
    CHECK ((kind='SEARCH' AND organization_id IS NULL) OR (kind='ANALYSIS' AND organization_id IS NOT NULL)),
    CHECK (cutoff=created_at AND expires_at=created_at+interval '24 hours'),
    CHECK (filters ? 'from' AND filters ? 'to' AND NOT filters ?| ARRAY['q','ip','rawQuery','queryText']),
    CHECK (content_hash=encode(sha256(file_content),'hex'))
);
CREATE INDEX analytics_report_expiration_idx ON analytics.report(expires_at);
CREATE TRIGGER analytics_report_immutable BEFORE UPDATE OR DELETE ON analytics.report
    FOR EACH ROW EXECUTE FUNCTION metadata.immutable_record();
