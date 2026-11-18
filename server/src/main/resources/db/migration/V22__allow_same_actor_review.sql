-- Authorized reviewers may decide their own submissions. Content and state protections remain.
CREATE OR REPLACE FUNCTION metadata.validate_decision()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM metadata.review_request q JOIN catalog.dataset_revision r ON r.id=q.rvsn_id
        WHERE q.id=NEW.rvw_dmnd_id AND q.stts_cd='SUBMITTED' AND r.stts_cd='IN_REVIEW'
          AND r.cn_hash_vl=NEW.rvw_cn_hash_vl) THEN
        RAISE EXCEPTION 'Decision requires submitted matching content' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$function$;

ALTER TABLE standard.dictionary_entry
    DROP CONSTRAINT dictionary_entry_check,
    DROP CONSTRAINT entry_last_editor_separation,
    ADD CONSTRAINT dictionary_entry_decision_requires_submission
        CHECK (dcsn_user_id IS NULL OR sbmsn_user_id IS NOT NULL);
