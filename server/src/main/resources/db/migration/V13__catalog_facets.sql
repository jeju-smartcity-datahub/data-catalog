-- Public discovery classifications are independent from credential-bearing hub connections.
-- No production regions/hubs or inferred legacy relationships are seeded.
CREATE TABLE catalog.discovery_lookup (
    id uuid PRIMARY KEY,
    kind varchar(10) NOT NULL CHECK (kind IN ('REGION','HUB')),
    code varchar(80) NOT NULL CHECK (code ~ '^[a-z0-9][a-z0-9_-]{0,79}$'),
    name varchar(200) NOT NULL CHECK (length(trim(name)) > 0),
    active boolean NOT NULL DEFAULT true,
    lock_version bigint NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (kind,code), UNIQUE (id,kind)
);
CREATE TABLE catalog.revision_discovery (
    revision_id uuid NOT NULL REFERENCES catalog.dataset_revision(id) ON DELETE RESTRICT,
    lookup_id uuid NOT NULL,
    kind varchar(10) NOT NULL,
    name_snapshot varchar(200) NOT NULL,
    PRIMARY KEY (revision_id,lookup_id),
    FOREIGN KEY (lookup_id,kind) REFERENCES catalog.discovery_lookup(id,kind) ON DELETE RESTRICT
);
CREATE INDEX revision_discovery_lookup_idx ON catalog.revision_discovery(lookup_id,revision_id);
CREATE TABLE catalog.revision_topic (
    revision_id uuid NOT NULL REFERENCES catalog.dataset_revision(id) ON DELETE RESTRICT,
    topic_id uuid NOT NULL REFERENCES classification.category(id) ON DELETE RESTRICT,
    name_snapshot varchar(200) NOT NULL,
    PRIMARY KEY (revision_id,topic_id)
);
CREATE INDEX revision_topic_lookup_idx ON catalog.revision_topic(topic_id,revision_id);

CREATE FUNCTION catalog.protect_discovery_reference() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; revision_state varchar(20);
BEGIN
    target := CASE WHEN TG_OP='DELETE' THEN OLD.revision_id ELSE NEW.revision_id END;
    SELECT state INTO revision_state FROM catalog.dataset_revision WHERE id=target FOR UPDATE;
    IF revision_state IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'Discovery references require a mutable draft' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' AND NEW.revision_id IS DISTINCT FROM OLD.revision_id THEN
        RAISE EXCEPTION 'Reference revision identity is immutable' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER discovery_reference_draft BEFORE INSERT OR UPDATE OR DELETE ON catalog.revision_discovery
    FOR EACH ROW EXECUTE FUNCTION catalog.protect_discovery_reference();
CREATE TRIGGER topic_reference_draft BEFORE INSERT OR UPDATE OR DELETE ON catalog.revision_topic
    FOR EACH ROW EXECUTE FUNCTION catalog.protect_discovery_reference();

CREATE FUNCTION catalog.protect_discovery_lookup() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Discovery lookups are deactivated, not deleted' USING ERRCODE='23514';
    END IF;
    IF (NEW.id,NEW.kind,NEW.code,NEW.created_at) IS DISTINCT FROM (OLD.id,OLD.kind,OLD.code,OLD.created_at) THEN
        RAISE EXCEPTION 'Discovery lookup identity is immutable' USING ERRCODE='23514';
    END IF;
    IF NOT NEW.active AND EXISTS (SELECT 1 FROM catalog.revision_discovery WHERE lookup_id=OLD.id) THEN
        RAISE EXCEPTION 'Referenced lookup cannot be deactivated' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER discovery_lookup_protected BEFORE UPDATE OR DELETE ON catalog.discovery_lookup
    FOR EACH ROW EXECUTE FUNCTION catalog.protect_discovery_lookup();

CREATE FUNCTION catalog.validate_discovery_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM catalog.discovery_lookup WHERE id=NEW.lookup_id AND kind=NEW.kind AND active FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Active matching discovery lookup required' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER discovery_reference_active BEFORE INSERT OR UPDATE ON catalog.revision_discovery
    FOR EACH ROW EXECUTE FUNCTION catalog.validate_discovery_reference();

CREATE FUNCTION catalog.validate_topic_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM classification.category t JOIN classification.category parent ON parent.id=t.parent_id
      JOIN catalog.dataset_revision r ON r.id=NEW.revision_id
      JOIN classification.category selected ON selected.id=r.primary_category_id
      WHERE t.id=NEW.topic_id AND t.active AND parent.active
        AND t.parent_id=coalesce(selected.parent_id,selected.id) FOR SHARE OF t,parent,selected;
    IF NOT FOUND THEN RAISE EXCEPTION 'Topic requires its active selected parent' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER topic_reference_parent BEFORE INSERT OR UPDATE ON catalog.revision_topic
    FOR EACH ROW EXECUTE FUNCTION catalog.validate_topic_reference();

CREATE FUNCTION catalog.protect_referenced_topic() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NOT NEW.active OR NEW.parent_id IS DISTINCT FROM OLD.parent_id) AND
      EXISTS (SELECT 1 FROM catalog.revision_topic WHERE topic_id=OLD.id) THEN
        RAISE EXCEPTION 'Referenced topic cannot be deactivated or moved' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER referenced_topic_protected BEFORE UPDATE ON classification.category
    FOR EACH ROW EXECUTE FUNCTION catalog.protect_referenced_topic();
