CREATE SCHEMA metadata;
CREATE SCHEMA audit;

ALTER TABLE catalog.dataset_revision ADD COLUMN content_hash varchar(64);
ALTER TABLE catalog.dataset_revision ADD COLUMN created_by uuid;
ALTER TABLE catalog.dataset_revision ADD COLUMN updated_by uuid;
ALTER TABLE catalog.dataset_revision ADD COLUMN updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE TABLE metadata.review_request (
    id uuid PRIMARY KEY,
    dataset_id uuid NOT NULL,
    revision_id uuid NOT NULL UNIQUE,
    content_hash varchar(64) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('SUBMITTED','APPROVED','REJECTED','WITHDRAWN')),
    submitted_by uuid NOT NULL,
    submitted_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lock_version bigint NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
    FOREIGN KEY (dataset_id,revision_id) REFERENCES catalog.dataset_revision(dataset_id,id),
    UNIQUE (id,content_hash)
);
CREATE UNIQUE INDEX review_one_pending_per_dataset ON metadata.review_request(dataset_id) WHERE status='SUBMITTED';
CREATE INDEX review_status_time_idx ON metadata.review_request(status,submitted_at DESC,id);

CREATE TABLE metadata.review_decision (
    id uuid PRIMARY KEY,
    review_request_id uuid NOT NULL UNIQUE,
    reviewed_content_hash varchar(64) NOT NULL,
    decision varchar(10) NOT NULL CHECK (decision IN ('APPROVE','REJECT')),
    reviewer_id uuid NOT NULL,
    reason varchar(2000),
    decided_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (review_request_id,reviewed_content_hash) REFERENCES metadata.review_request(id,content_hash),
    CHECK (decision <> 'REJECT' OR length(trim(reason)) > 0 AND reason IS NOT NULL)
);

ALTER TABLE catalog.publication ADD COLUMN review_decision_id uuid REFERENCES metadata.review_decision(id);
ALTER TABLE catalog.publication ADD COLUMN published_by uuid;
ALTER TABLE catalog.publication ADD COLUMN lock_version bigint NOT NULL DEFAULT 0;
ALTER TABLE catalog.publication ADD COLUMN withdrawal_reason varchar(2000);

CREATE TABLE metadata.idempotency_record (
    principal_scope varchar(80) NOT NULL,
    operation varchar(200) NOT NULL,
    key_digest varchar(64) NOT NULL,
    request_hash varchar(64) NOT NULL,
    result jsonb NOT NULL,
    expires_at timestamptz NOT NULL DEFAULT (CURRENT_TIMESTAMP + interval '24 hours'),
    PRIMARY KEY (principal_scope,operation,key_digest)
);

CREATE TABLE audit.audit_event (
    id uuid PRIMARY KEY,
    occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_user_id uuid NOT NULL,
    organization_id uuid REFERENCES identity.organization(id),
    dataset_id uuid REFERENCES catalog.dataset(id),
    action varchar(80) NOT NULL,
    target_type varchar(80) NOT NULL,
    target_id uuid NOT NULL,
    request_id varchar(100) NOT NULL,
    result varchar(20) NOT NULL DEFAULT 'SUCCESS',
    change_summary jsonb NOT NULL CHECK (jsonb_typeof(change_summary)='object')
);
CREATE INDEX audit_dataset_time_idx ON audit.audit_event(dataset_id,occurred_at DESC,id);
CREATE INDEX audit_org_time_idx ON audit.audit_event(organization_id,occurred_at DESC,id);

CREATE FUNCTION metadata.immutable_record() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Immutable workflow history' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER review_decision_immutable BEFORE UPDATE OR DELETE ON metadata.review_decision
    FOR EACH ROW EXECUTE FUNCTION metadata.immutable_record();
CREATE TRIGGER audit_event_immutable BEFORE UPDATE OR DELETE ON audit.audit_event
    FOR EACH ROW EXECUTE FUNCTION metadata.immutable_record();

CREATE FUNCTION metadata.validate_decision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM metadata.review_request q JOIN catalog.dataset_revision r ON r.id=q.revision_id
        WHERE q.id=NEW.review_request_id AND q.status='SUBMITTED' AND r.state='IN_REVIEW'
          AND r.content_hash=NEW.reviewed_content_hash) THEN
        RAISE EXCEPTION 'Decision requires submitted matching content' USING ERRCODE='23514';
    END IF;
    IF EXISTS (SELECT 1 FROM metadata.review_request q JOIN catalog.dataset_revision r ON r.id=q.revision_id
        WHERE q.id=NEW.review_request_id AND (q.submitted_by=NEW.reviewer_id OR r.updated_by=NEW.reviewer_id OR r.created_by=NEW.reviewer_id)) THEN
        RAISE EXCEPTION 'Self review is prohibited' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER decision_separation BEFORE INSERT ON metadata.review_decision FOR EACH ROW EXECUTE FUNCTION metadata.validate_decision();

-- V1 approved snapshots and explicitly labelled development seeds remain readable.
-- New managed revisions have a hash and must reference the matching approval.
CREATE FUNCTION metadata.validate_publication_approval() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM catalog.dataset_revision WHERE id=NEW.revision_id AND (content_hash IS NOT NULL OR created_by IS NOT NULL))
        AND NOT EXISTS (SELECT 1 FROM metadata.review_decision d JOIN metadata.review_request q ON q.id=d.review_request_id
            JOIN catalog.dataset_revision r ON r.id=q.revision_id
            WHERE d.id=NEW.review_decision_id AND q.dataset_id=NEW.dataset_id AND q.revision_id=NEW.revision_id
              AND d.decision='APPROVE' AND q.status='APPROVED' AND r.content_hash=d.reviewed_content_hash) THEN
        RAISE EXCEPTION 'Publication requires matching approval' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' AND (NEW.review_decision_id,NEW.published_by) IS DISTINCT FROM (OLD.review_decision_id,OLD.published_by) THEN
        RAISE EXCEPTION 'Publication approval is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION metadata.protect_review_request() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR (to_jsonb(NEW)-'status'-'lock_version') IS DISTINCT FROM (to_jsonb(OLD)-'status'-'lock_version') THEN
        RAISE EXCEPTION 'Review submission is immutable' USING ERRCODE='23514';
    END IF;
    IF OLD.status <> 'SUBMITTED' OR NEW.status NOT IN ('APPROVED','REJECTED','WITHDRAWN') OR NEW.lock_version <> OLD.lock_version+1 THEN
        RAISE EXCEPTION 'Invalid review state transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER review_request_content_immutable BEFORE UPDATE OR DELETE ON metadata.review_request
    FOR EACH ROW EXECUTE FUNCTION metadata.protect_review_request();
CREATE TRIGGER publication_matching_approval BEFORE INSERT OR UPDATE ON catalog.publication
    FOR EACH ROW EXECUTE FUNCTION metadata.validate_publication_approval();

CREATE OR REPLACE FUNCTION catalog.protect_approved_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state IN ('APPROVED','REJECTED') THEN
        RAISE EXCEPTION 'Decided revision is immutable' USING ERRCODE='23514';
    END IF;
    IF OLD.state='IN_REVIEW' AND (TG_OP='DELETE' OR
       (to_jsonb(NEW)-'state') IS DISTINCT FROM (to_jsonb(OLD)-'state')) THEN
        RAISE EXCEPTION 'Submitted revision content is immutable' USING ERRCODE='23514';
    END IF;
    IF OLD.state='IN_REVIEW' AND NEW.state NOT IN ('APPROVED','REJECTED') THEN
        RAISE EXCEPTION 'Submitted revision requires a review outcome' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
