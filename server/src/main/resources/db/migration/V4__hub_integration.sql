-- P4: durable integration foundation. No sample connections or runtime adapters.
CREATE SCHEMA integration;

CREATE TABLE integration.source_connection (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES identity.organization(id),
    name varchar(200) NOT NULL CHECK (length(trim(name)) > 0),
    adapter_code varchar(80) NOT NULL,
    endpoint text,
    secret_reference varchar(200),
    enabled boolean NOT NULL DEFAULT false,
    timeout_seconds integer NOT NULL CHECK (timeout_seconds BETWEEN 1 AND 30),
    max_attempts integer NOT NULL CHECK (max_attempts BETWEEN 1 AND 5),
    max_pages integer NOT NULL CHECK (max_pages BETWEEN 1 AND 100),
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (NOT enabled OR endpoint IS NOT NULL)
);
CREATE INDEX source_connection_org ON integration.source_connection(organization_id, created_at, id);

CREATE TABLE integration.sync_run (
    id uuid PRIMARY KEY,
    source_connection_id uuid NOT NULL REFERENCES integration.source_connection(id),
    requested_by uuid NOT NULL,
    kind varchar(20) NOT NULL CHECK (kind IN ('CHECK', 'SYNC')),
    status varchar(30) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'PARTIAL_FAILED', 'FAILED')),
    idempotency_digest char(64) NOT NULL,
    request_hash char(64) NOT NULL,
    configuration_version bigint NOT NULL,
    mapping_version varchar(80) NOT NULL,
    attempt integer NOT NULL DEFAULT 0,
    lease_token uuid,
    lease_until timestamptz,
    available_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    checkpoint_before text,
    checkpoint_after text,
    error_code varchar(80),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at timestamptz,
    finished_at timestamptz,
    UNIQUE(source_connection_id, requested_by, kind, idempotency_digest),
    CHECK ((status = 'RUNNING') = (lease_token IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE UNIQUE INDEX one_active_run_per_connection ON integration.sync_run(source_connection_id)
    WHERE status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT');
CREATE INDEX claim_sync_run ON integration.sync_run(available_at, created_at)
    WHERE status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT');

CREATE TABLE integration.sync_checkpoint (
    source_connection_id uuid PRIMARY KEY REFERENCES integration.source_connection(id),
    cursor text,
    last_successful_run_id uuid REFERENCES integration.sync_run(id),
    lock_version bigint NOT NULL DEFAULT 0
);

-- Normalized, non-public review candidates. No upstream raw bodies or credentials are retained.
CREATE TABLE integration.source_metadata (
    id uuid PRIMARY KEY,
    source_connection_id uuid NOT NULL REFERENCES integration.source_connection(id),
    external_id varchar(300) NOT NULL,
    source_hash char(64) NOT NULL,
    source_revision varchar(300),
    candidate jsonb NOT NULL CHECK (jsonb_typeof(candidate) = 'object'),
    mapping_version varchar(80) NOT NULL,
    revision_no integer NOT NULL DEFAULT 1,
    last_run_id uuid NOT NULL REFERENCES integration.sync_run(id),
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(source_connection_id, external_id)
);
CREATE TABLE integration.sync_item (
    id uuid PRIMARY KEY,
    sync_run_id uuid NOT NULL REFERENCES integration.sync_run(id),
    external_id varchar(300) NOT NULL,
    source_hash char(64),
    status varchar(20) NOT NULL CHECK (status IN ('CANDIDATE', 'UNCHANGED', 'FAILED')),
    error_code varchar(80),
    attempt integer NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(sync_run_id, external_id)
);
CREATE INDEX sync_item_status ON integration.sync_item(sync_run_id, status, external_id);

CREATE TABLE integration.connection_event (
    id uuid PRIMARY KEY,
    connection_id uuid NOT NULL REFERENCES integration.source_connection(id),
    actor_id uuid NOT NULL,
    action varchar(40) NOT NULL,
    request_id varchar(64) NOT NULL,
    configuration_version bigint NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE FUNCTION integration.protect_connection_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Integration audit events are append-only' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER connection_event_append_only BEFORE UPDATE OR DELETE ON integration.connection_event
    FOR EACH ROW EXECUTE FUNCTION integration.protect_connection_event();
