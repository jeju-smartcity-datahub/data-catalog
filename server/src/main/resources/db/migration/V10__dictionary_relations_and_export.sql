-- V8 was not applied: the parallel V9 integration work reached the test DB first.
-- Incremental dictionary relations preserve all previously applied migrations.
CREATE TABLE standard.word_definition (
    entry_id uuid PRIMARY KEY REFERENCES standard.dictionary_entry(id) ON DELETE RESTRICT,
    english_name varchar(200),
    abbreviation varchar(100),
    synonyms jsonb NOT NULL DEFAULT '[]' CHECK(jsonb_typeof(synonyms)='array' AND jsonb_array_length(synonyms)<=30)
);
CREATE TABLE standard.domain_definition (
    entry_id uuid PRIMARY KEY REFERENCES standard.dictionary_entry(id) ON DELETE RESTRICT,
    max_length integer CHECK(max_length BETWEEN 1 AND 1000000),
    numeric_precision integer CHECK(numeric_precision BETWEEN 1 AND 1000),
    numeric_scale integer CHECK(numeric_scale BETWEEN 0 AND 1000),
    format varchar(200),
    unit varchar(100),
    allowed_values jsonb NOT NULL DEFAULT '[]' CHECK(jsonb_typeof(allowed_values)='array' AND jsonb_array_length(allowed_values)<=1000),
    CHECK(numeric_scale IS NULL OR numeric_precision IS NOT NULL AND numeric_scale<=numeric_precision)
);
CREATE TABLE standard.term_definition (
    entry_id uuid PRIMARY KEY REFERENCES standard.dictionary_entry(id) ON DELETE RESTRICT,
    physical_name varchar(200) NOT NULL CHECK(length(trim(physical_name))>0),
    domain_entry_id uuid NOT NULL REFERENCES standard.dictionary_entry(id) ON DELETE RESTRICT,
    CHECK(entry_id<>domain_entry_id)
);
CREATE TABLE standard.term_word (
    term_entry_id uuid NOT NULL REFERENCES standard.term_definition(entry_id) ON DELETE RESTRICT,
    word_entry_id uuid NOT NULL REFERENCES standard.dictionary_entry(id) ON DELETE RESTRICT,
    ordinal integer NOT NULL CHECK(ordinal BETWEEN 0 AND 31),
    PRIMARY KEY(term_entry_id,ordinal),
    CHECK(term_entry_id<>word_entry_id)
);
CREATE INDEX term_domain_idx ON standard.term_definition(domain_entry_id);
CREATE INDEX term_word_reference_idx ON standard.term_word(word_entry_id);

CREATE FUNCTION standard.protect_relationship() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    record_json jsonb; old_json jsonb; entry_key uuid; expected_kind text; entry_row record;
BEGIN
    record_json=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    IF TG_OP='UPDATE' THEN old_json=to_jsonb(OLD); END IF;
    entry_key=coalesce(record_json->>'entry_id',record_json->>'term_entry_id')::uuid;
    IF TG_OP='UPDATE' AND coalesce(record_json->>'entry_id',record_json->>'term_entry_id')
        IS DISTINCT FROM coalesce(old_json->>'entry_id',old_json->>'term_entry_id') THEN
        RAISE EXCEPTION 'Relationship identity is immutable' USING ERRCODE='23514';
    END IF;
    expected_kind=CASE TG_TABLE_NAME WHEN 'word_definition' THEN 'WORD' WHEN 'domain_definition' THEN 'DOMAIN' ELSE 'TERM' END;
    SELECT e.kind,e.state,d.scope INTO entry_row FROM standard.dictionary_entry e JOIN standard.dictionary d ON d.id=e.dictionary_id
        WHERE e.id=entry_key FOR UPDATE OF e;
    IF NOT FOUND OR entry_row.kind<>expected_kind OR entry_row.state<>'DRAFT' OR entry_row.scope<>'ORGANIZATION' THEN
        RAISE EXCEPTION 'Only matching organization draft relationships are editable' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER word_relationship_guard BEFORE INSERT OR UPDATE OR DELETE ON standard.word_definition FOR EACH ROW EXECUTE FUNCTION standard.protect_relationship();
CREATE TRIGGER domain_relationship_guard BEFORE INSERT OR UPDATE OR DELETE ON standard.domain_definition FOR EACH ROW EXECUTE FUNCTION standard.protect_relationship();
CREATE TRIGGER term_relationship_guard BEFORE INSERT OR UPDATE OR DELETE ON standard.term_definition FOR EACH ROW EXECUTE FUNCTION standard.protect_relationship();
CREATE TRIGGER term_word_relationship_guard BEFORE INSERT OR UPDATE OR DELETE ON standard.term_word FOR EACH ROW EXECUTE FUNCTION standard.protect_relationship();

CREATE FUNCTION standard.validate_term_review() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE domain_row record; owner_org uuid;
BEGIN
    IF NEW.kind='TERM' AND NEW.state IN ('IN_REVIEW','APPROVED') AND (TG_OP='INSERT' OR NEW.state IS DISTINCT FROM OLD.state) THEN
        SELECT organization_id INTO owner_org FROM standard.dictionary WHERE id=NEW.dictionary_id;
        SELECT e.kind,e.state,e.data_type,d.scope,d.organization_id INTO domain_row FROM standard.term_definition t
            JOIN standard.dictionary_entry e ON e.id=t.domain_entry_id JOIN standard.dictionary d ON d.id=e.dictionary_id WHERE t.entry_id=NEW.id;
        IF NOT FOUND OR domain_row.kind<>'DOMAIN' OR domain_row.state<>'APPROVED' OR domain_row.data_type<>NEW.data_type
            OR (domain_row.scope<>'PUBLIC' AND domain_row.organization_id IS DISTINCT FROM owner_org) THEN
            RAISE EXCEPTION 'Term requires an approved compatible domain' USING ERRCODE='23514';
        END IF;
        IF NOT EXISTS(SELECT 1 FROM standard.term_word WHERE term_entry_id=NEW.id)
            OR EXISTS(SELECT 1 FROM standard.term_word tw JOIN standard.dictionary_entry e ON e.id=tw.word_entry_id
                JOIN standard.dictionary d ON d.id=e.dictionary_id WHERE tw.term_entry_id=NEW.id
                AND (e.kind<>'WORD' OR e.state<>'APPROVED' OR (d.scope<>'PUBLIC' AND d.organization_id IS DISTINCT FROM owner_org))) THEN
            RAISE EXCEPTION 'Term requires approved words in permitted scope' USING ERRCODE='23514';
        END IF;
        IF (SELECT max(ordinal)+1<>count(*) FROM standard.term_word WHERE term_entry_id=NEW.id) THEN
            RAISE EXCEPTION 'Term word order must be contiguous' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER term_review_contract BEFORE INSERT OR UPDATE OF state ON standard.dictionary_entry
    FOR EACH ROW EXECUTE FUNCTION standard.validate_term_review();

CREATE TABLE standard.dictionary_export (
    id uuid PRIMARY KEY,
    dictionary_id uuid NOT NULL REFERENCES standard.dictionary(id) ON DELETE RESTRICT,
    requested_by uuid NOT NULL,
    kind varchar(10) NOT NULL CHECK(kind IN ('WORD','TERM','DOMAIN')),
    query varchar(200),
    state_filter varchar(20) CHECK(state_filter IN ('DRAFT','IN_REVIEW','APPROVED','REJECTED')),
    key_digest varchar(64) NOT NULL,
    request_hash varchar(64) NOT NULL,
    entry_count integer NOT NULL CHECK(entry_count BETWEEN 0 AND 5000),
    file_content bytea NOT NULL CHECK(octet_length(file_content)<=8388608),
    content_hash varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at timestamptz NOT NULL DEFAULT(CURRENT_TIMESTAMP+interval '24 hours'),
    UNIQUE(dictionary_id,requested_by,key_digest)
);
CREATE INDEX dictionary_export_expiration_idx ON standard.dictionary_export(expires_at);
