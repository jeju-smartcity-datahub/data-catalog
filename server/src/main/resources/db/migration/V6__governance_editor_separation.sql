ALTER TABLE standard.dictionary_entry ADD COLUMN updated_by uuid;
ALTER TABLE standard.dictionary_entry ADD CONSTRAINT entry_last_editor_separation
    CHECK (decided_by IS NULL OR decided_by <> coalesce(updated_by,created_by));

CREATE OR REPLACE FUNCTION standard.protect_entry() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state IN ('APPROVED','REJECTED') THEN
        RAISE EXCEPTION 'Decided dictionary entries are immutable' USING ERRCODE='23514';
    END IF;
    IF EXISTS (SELECT 1 FROM standard.dictionary WHERE id=OLD.dictionary_id AND scope='PUBLIC') THEN
        RAISE EXCEPTION 'Public dictionary source is immutable' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN
        IF OLD.state <> 'DRAFT' THEN RAISE EXCEPTION 'Submitted dictionary entries cannot be deleted' USING ERRCODE='23514'; END IF;
        RETURN OLD;
    END IF;
    IF (NEW.dictionary_id,NEW.kind,NEW.code,NEW.created_by) IS DISTINCT FROM (OLD.dictionary_id,OLD.kind,OLD.code,OLD.created_by)
       OR (OLD.state <> 'DRAFT' AND (NEW.name,NEW.definition,NEW.data_type,NEW.updated_by,NEW.submitted_by)
           IS DISTINCT FROM (OLD.name,OLD.definition,OLD.data_type,OLD.updated_by,OLD.submitted_by)) THEN
        RAISE EXCEPTION 'Dictionary identity and review content are immutable' USING ERRCODE='23514';
    END IF;
    IF OLD.state='DRAFT' AND NEW.state NOT IN ('DRAFT','IN_REVIEW')
       OR OLD.state='IN_REVIEW' AND NEW.state NOT IN ('APPROVED','REJECTED') THEN
        RAISE EXCEPTION 'Invalid dictionary review transition' USING ERRCODE='23514';
    END IF;
    IF NEW.state='IN_REVIEW' AND NEW.submitted_by IS NULL THEN
        RAISE EXCEPTION 'Dictionary submission actor required' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
