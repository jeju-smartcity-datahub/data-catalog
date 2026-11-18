-- P3 incremental governance. Official public dictionaries are imported only after source validation.
CREATE SCHEMA standard;
CREATE SCHEMA analytics;

CREATE TABLE standard.dictionary (
    id uuid PRIMARY KEY,
    scope varchar(20) NOT NULL CHECK (scope IN ('PUBLIC','ORGANIZATION')),
    organization_id uuid REFERENCES identity.organization(id) ON DELETE RESTRICT,
    code varchar(80) NOT NULL,
    name varchar(200) NOT NULL,
    source_uri varchar(2000),
    version_label varchar(100) NOT NULL,
    source_checksum varchar(64),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((scope='PUBLIC' AND organization_id IS NULL) OR (scope='ORGANIZATION' AND organization_id IS NOT NULL)),
    UNIQUE NULLS NOT DISTINCT (scope,organization_id,code,version_label)
);
CREATE TABLE standard.dictionary_entry (
    id uuid PRIMARY KEY,
    dictionary_id uuid NOT NULL REFERENCES standard.dictionary(id) ON DELETE RESTRICT,
    kind varchar(10) NOT NULL CHECK (kind IN ('WORD','TERM','DOMAIN')),
    code varchar(100) NOT NULL,
    name varchar(200) NOT NULL,
    definition varchar(4000) NOT NULL,
    data_type varchar(20) NOT NULL CHECK (data_type IN ('STRING','INTEGER','NUMBER','BOOLEAN','DATE','DATETIME','OBJECT','ARRAY')),
    state varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT','IN_REVIEW','APPROVED','REJECTED')),
    lock_version bigint NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
    created_by uuid NOT NULL,
    submitted_by uuid,
    decided_by uuid,
    decision_reason varchar(2000),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(dictionary_id,kind,code),
    CHECK (decided_by IS NULL OR (submitted_by IS NOT NULL AND submitted_by <> decided_by AND created_by <> decided_by)),
    CHECK (state NOT IN ('APPROVED','REJECTED') OR decided_by IS NOT NULL),
    CHECK (state <> 'REJECTED' OR (decision_reason IS NOT NULL AND length(trim(decision_reason)) > 0))
);
CREATE FUNCTION standard.protect_dictionary() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Dictionary source and version are immutable; create a new version' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER dictionary_source_immutable BEFORE UPDATE OR DELETE ON standard.dictionary
    FOR EACH ROW EXECUTE FUNCTION standard.protect_dictionary();
CREATE FUNCTION standard.protect_entry() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state='APPROVED' THEN
        RAISE EXCEPTION 'Approved dictionary entries are immutable' USING ERRCODE='23514';
    END IF;
    IF EXISTS (SELECT 1 FROM standard.dictionary WHERE id=OLD.dictionary_id AND scope='PUBLIC') THEN
        RAISE EXCEPTION 'Public dictionary source is immutable' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    IF (NEW.dictionary_id,NEW.kind,NEW.code,NEW.created_by) IS DISTINCT FROM (OLD.dictionary_id,OLD.kind,OLD.code,OLD.created_by)
       OR (OLD.state <> 'DRAFT' AND (NEW.name,NEW.definition,NEW.data_type) IS DISTINCT FROM (OLD.name,OLD.definition,OLD.data_type)) THEN
        RAISE EXCEPTION 'Dictionary identity and review content are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER dictionary_entry_immutable BEFORE UPDATE OR DELETE ON standard.dictionary_entry
    FOR EACH ROW EXECUTE FUNCTION standard.protect_entry();

CREATE FUNCTION classification.protect_category_use() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR (OLD.active AND NOT NEW.active) THEN
        IF EXISTS(SELECT 1 FROM catalog.dataset_revision WHERE primary_category_id=OLD.id)
           OR EXISTS(SELECT 1 FROM classification.category WHERE parent_id=OLD.id) THEN
            RAISE EXCEPTION 'Category is referenced' USING ERRCODE='23514';
        END IF;
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER category_use_guard BEFORE UPDATE OR DELETE ON classification.category
    FOR EACH ROW EXECUTE FUNCTION classification.protect_category_use();
CREATE FUNCTION classification.require_active_parent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' AND (NEW.code,NEW.parent_id) IS DISTINCT FROM (OLD.code,OLD.parent_id) THEN
        RAISE EXCEPTION 'Category identity is immutable' USING ERRCODE='23514';
    END IF;
    IF NEW.parent_id IS NOT NULL THEN
        PERFORM 1 FROM classification.category WHERE id=NEW.parent_id AND parent_id IS NULL AND active FOR SHARE;
        IF NOT FOUND THEN RAISE EXCEPTION 'Active root category required' USING ERRCODE='23514'; END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER category_active_parent BEFORE INSERT OR UPDATE ON classification.category
    FOR EACH ROW EXECUTE FUNCTION classification.require_active_parent();
CREATE FUNCTION classification.require_active_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.primary_category_id IS NOT NULL THEN
        PERFORM 1 FROM classification.category WHERE id=NEW.primary_category_id AND active FOR SHARE;
        IF NOT FOUND THEN RAISE EXCEPTION 'Active category required' USING ERRCODE='23514'; END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER revision_active_category BEFORE INSERT OR UPDATE OF primary_category_id ON catalog.dataset_revision
    FOR EACH ROW EXECUTE FUNCTION classification.require_active_reference();

CREATE TABLE analytics.search_event (
    id uuid PRIMARY KEY,
    request_id varchar(100) NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    has_query boolean NOT NULL,
    normalized_filters jsonb NOT NULL CHECK (jsonb_typeof(normalized_filters)='object'),
    result_count bigint NOT NULL CHECK(result_count >= 0),
    duration_ms bigint NOT NULL CHECK(duration_ms >= 0),
    search_scope varchar(20) NOT NULL DEFAULT 'PUBLIC' CHECK(search_scope='PUBLIC')
);
CREATE INDEX search_event_time_idx ON analytics.search_event(occurred_at DESC,id);
CREATE INDEX search_event_request_idx ON analytics.search_event(request_id);
CREATE TABLE analytics.analysis_run (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES identity.organization(id) ON DELETE RESTRICT,
    dataset_revision_id uuid NOT NULL REFERENCES catalog.dataset_revision(id) ON DELETE RESTRICT,
    kind varchar(30) NOT NULL CHECK(kind IN ('DEFINITION_EXPORT','FIELD_MAPPING')),
    engine_version varchar(50) NOT NULL,
    requested_by uuid NOT NULL,
    started_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status varchar(20) NOT NULL CHECK(status='SUCCEEDED'),
    field_count integer NOT NULL CHECK(field_count>=0),
    source_hash varchar(64) NOT NULL
);
CREATE INDEX analysis_run_org_idx ON analytics.analysis_run(organization_id,started_at DESC,id);
