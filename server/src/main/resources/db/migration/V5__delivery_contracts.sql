-- Contract storage only: no upstream endpoints, credentials, or demo contracts are seeded.
CREATE SCHEMA delivery;
CREATE SCHEMA apidocs;

CREATE TABLE delivery.data_service_version (
    id uuid PRIMARY KEY,
    dataset_id uuid NOT NULL REFERENCES catalog.dataset(id) ON DELETE RESTRICT,
    contract_version integer NOT NULL CHECK (contract_version > 0),
    delivery_mode varchar(20) NOT NULL CHECK (delivery_mode IN ('LINK', 'PROXY', 'MATERIALIZED')),
    access_policy varchar(20) NOT NULL CHECK (access_policy IN ('PUBLIC', 'UPSTREAM_AUTH', 'RESTRICTED')),
    state varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT', 'VERIFIED', 'REVOKED')),
    verification_level varchar(20) NOT NULL CHECK (verification_level IN ('FIXTURE', 'REAL_CONTRACT')),
    schema_snapshot jsonb NOT NULL CHECK (jsonb_typeof(schema_snapshot) = 'object'),
    schema_hash varchar(64) NOT NULL CHECK (schema_hash ~ '^[0-9a-f]{64}$'),
    contract_snapshot jsonb NOT NULL CHECK (jsonb_typeof(contract_snapshot) = 'object'),
    contract_hash varchar(64) NOT NULL CHECK (contract_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (dataset_id, contract_version),
    UNIQUE (dataset_id, id)
);

CREATE TABLE delivery.publication_service (
    dataset_id uuid NOT NULL,
    publication_id uuid NOT NULL,
    service_version_id uuid NOT NULL,
    PRIMARY KEY (publication_id, service_version_id),
    FOREIGN KEY (dataset_id, publication_id) REFERENCES catalog.publication(dataset_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (dataset_id, service_version_id) REFERENCES delivery.data_service_version(dataset_id, id) ON DELETE RESTRICT
);

CREATE TABLE apidocs.api_document_version (
    id uuid PRIMARY KEY,
    service_version_id uuid NOT NULL REFERENCES delivery.data_service_version(id) ON DELETE RESTRICT,
    document_revision integer NOT NULL CHECK (document_revision > 0),
    document_kind varchar(20) NOT NULL CHECK (document_kind = 'OPENAPI'),
    openapi_snapshot jsonb NOT NULL CHECK (jsonb_typeof(openapi_snapshot) = 'object'),
    spec_hash varchar(64) NOT NULL CHECK (spec_hash ~ '^[0-9a-f]{64}$'),
    schema_hash varchar(64) NOT NULL CHECK (schema_hash ~ '^[0-9a-f]{64}$'),
    generator_version varchar(40) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (service_version_id, document_revision)
);

CREATE FUNCTION delivery.protect_verified_contract() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state <> 'DRAFT' THEN
        IF TG_OP = 'DELETE' OR (to_jsonb(NEW) - 'state') IS DISTINCT FROM (to_jsonb(OLD) - 'state')
            OR NEW.state <> 'REVOKED' THEN
            RAISE EXCEPTION 'Verified contracts are immutable; revocation is allowed' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER verified_contract_immutable BEFORE UPDATE OR DELETE ON delivery.data_service_version
    FOR EACH ROW EXECUTE FUNCTION delivery.protect_verified_contract();

CREATE FUNCTION delivery.require_real_publication_contract() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM delivery.data_service_version s
        JOIN catalog.publication p ON p.id = NEW.publication_id AND p.dataset_id = s.dataset_id
        WHERE s.id = NEW.service_version_id AND s.dataset_id = NEW.dataset_id
          AND s.state = 'VERIFIED' AND s.verification_level = 'REAL_CONTRACT'
          AND ((s.delivery_mode = 'LINK' AND p.access_state = 'LINK_READY')
            OR (s.delivery_mode IN ('PROXY', 'MATERIALIZED') AND p.access_state = 'SERVICE_READY'))
          AND p.state = 'PUBLISHED') THEN
        RAISE EXCEPTION 'Publication requires a verified real delivery contract and matching access state' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER real_publication_contract BEFORE INSERT OR UPDATE ON delivery.publication_service
    FOR EACH ROW EXECUTE FUNCTION delivery.require_real_publication_contract();

CREATE FUNCTION apidocs.protect_document_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'API document snapshots are immutable' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER document_snapshot_immutable BEFORE UPDATE OR DELETE ON apidocs.api_document_version
    FOR EACH ROW EXECUTE FUNCTION apidocs.protect_document_snapshot();

CREATE FUNCTION apidocs.require_matching_schema() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM delivery.data_service_version s WHERE s.id = NEW.service_version_id
        AND s.schema_hash = NEW.schema_hash AND s.state <> 'REVOKED') THEN
        RAISE EXCEPTION 'API document must match the service schema' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER document_matching_schema BEFORE INSERT ON apidocs.api_document_version
    FOR EACH ROW EXECUTE FUNCTION apidocs.require_matching_schema();
