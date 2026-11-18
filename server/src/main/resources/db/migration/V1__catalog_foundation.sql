-- P1 foundation. Review requests/decisions, full metadata and write APIs are P2 migrations.
CREATE SCHEMA identity;
CREATE SCHEMA classification;
CREATE SCHEMA catalog;

CREATE TABLE identity.organization (
    id uuid PRIMARY KEY,
    code varchar(80) NOT NULL UNIQUE,
    name varchar(200) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    lock_version bigint NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE classification.category (
    id uuid PRIMARY KEY,
    parent_id uuid REFERENCES classification.category(id) ON DELETE RESTRICT,
    code varchar(80) NOT NULL UNIQUE,
    name varchar(200) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    ordinal integer NOT NULL DEFAULT 0 CHECK (ordinal >= 0),
    lock_version bigint NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (parent_id IS NULL OR parent_id <> id)
);

CREATE TABLE catalog.dataset (
    id uuid PRIMARY KEY,
    owner_organization_id uuid NOT NULL REFERENCES identity.organization(id) ON DELETE RESTRICT,
    slug varchar(160) NOT NULL UNIQUE,
    lifecycle_status varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (lifecycle_status IN ('ACTIVE', 'RETIRED')),
    current_publication_id uuid,
    lock_version bigint NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE catalog.dataset_revision (
    id uuid PRIMARY KEY,
    dataset_id uuid NOT NULL REFERENCES catalog.dataset(id) ON DELETE RESTRICT,
    revision_no integer NOT NULL CHECK (revision_no > 0),
    state varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT', 'IN_REVIEW', 'APPROVED', 'REJECTED')),
    title varchar(300) NOT NULL CHECK (length(trim(title)) > 0),
    description text NOT NULL DEFAULT '',
    provider_organization_id uuid NOT NULL REFERENCES identity.organization(id) ON DELETE RESTRICT,
    primary_category_id uuid REFERENCES classification.category(id) ON DELETE RESTRICT,
    visibility varchar(20) NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PUBLIC', 'ORGANIZATION', 'PRIVATE')),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(metadata) = 'object'),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (dataset_id, revision_no),
    UNIQUE (dataset_id, id)
);
CREATE UNIQUE INDEX dataset_single_draft ON catalog.dataset_revision(dataset_id) WHERE state = 'DRAFT';

CREATE TABLE catalog.publication (
    id uuid PRIMARY KEY,
    dataset_id uuid NOT NULL REFERENCES catalog.dataset(id) ON DELETE RESTRICT,
    revision_id uuid NOT NULL UNIQUE,
    publication_no integer NOT NULL CHECK (publication_no > 0),
    state varchar(20) NOT NULL CHECK (state IN ('PUBLISHED', 'WITHDRAWN')),
    access_state varchar(30) NOT NULL DEFAULT 'METADATA_ONLY'
        CHECK (access_state IN ('METADATA_ONLY', 'LINK_READY', 'SERVICE_READY', 'UNAVAILABLE')),
    published_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    withdrawn_at timestamptz,
    FOREIGN KEY (dataset_id, revision_id) REFERENCES catalog.dataset_revision(dataset_id, id) ON DELETE RESTRICT,
    UNIQUE (dataset_id, publication_no),
    UNIQUE (dataset_id, id),
    CHECK ((state = 'PUBLISHED' AND withdrawn_at IS NULL) OR (state = 'WITHDRAWN' AND withdrawn_at IS NOT NULL))
);
ALTER TABLE catalog.dataset ADD CONSTRAINT dataset_current_publication_same_dataset
    FOREIGN KEY (id, current_publication_id) REFERENCES catalog.publication(dataset_id, id) ON DELETE RESTRICT;

CREATE INDEX dataset_owner_idx ON catalog.dataset(owner_organization_id, lifecycle_status, updated_at DESC, id);
CREATE INDEX publication_order_idx ON catalog.publication(published_at DESC, dataset_id) WHERE state = 'PUBLISHED';
CREATE INDEX revision_provider_idx ON catalog.dataset_revision(provider_organization_id);
CREATE INDEX revision_category_idx ON catalog.dataset_revision(primary_category_id);

CREATE FUNCTION catalog.protect_approved_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state = 'APPROVED' THEN
        RAISE EXCEPTION 'Approved revision is immutable' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER approved_revision_immutable BEFORE UPDATE OR DELETE ON catalog.dataset_revision
    FOR EACH ROW EXECUTE FUNCTION catalog.protect_approved_revision();

CREATE FUNCTION catalog.require_approved_publication() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.state = 'PUBLISHED' AND NOT EXISTS (
        SELECT 1 FROM catalog.dataset_revision r
        WHERE r.id = NEW.revision_id AND r.dataset_id = NEW.dataset_id AND r.state = 'APPROVED'
    ) THEN
        RAISE EXCEPTION 'Publication requires an approved revision of the same dataset' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.dataset_id, NEW.revision_id, NEW.publication_no, NEW.published_at)
        IS DISTINCT FROM (OLD.dataset_id, OLD.revision_id, OLD.publication_no, OLD.published_at) THEN
        RAISE EXCEPTION 'Publication identity and snapshot are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER publication_approved_revision BEFORE INSERT OR UPDATE ON catalog.publication
    FOR EACH ROW EXECUTE FUNCTION catalog.require_approved_publication();
