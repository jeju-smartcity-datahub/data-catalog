-- Standard column names and non-truncating storage domains. Existing row values and API contracts are retained.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';

-- Reject padded legacy hashes before changing their storage type; do not trim stored data.
DO $audit_hashes$
BEGIN
    IF EXISTS (SELECT 1 FROM integration.candidate_application WHERE sou_hash_vl IS NOT NULL AND length(sou_hash_vl::text) <> 64) THEN
        RAISE EXCEPTION 'Hash length must be 64 before upgrade: integration.candidate_application.sou_hash_vl' USING ERRCODE='22000';
    END IF;
    IF EXISTS (SELECT 1 FROM integration.candidate_application WHERE idmpt_key_hash_vl IS NOT NULL AND length(idmpt_key_hash_vl::text) <> 64) THEN
        RAISE EXCEPTION 'Hash length must be 64 before upgrade: integration.candidate_application.idmpt_key_hash_vl' USING ERRCODE='22000';
    END IF;
    IF EXISTS (SELECT 1 FROM integration.candidate_application WHERE dmnd_hash_vl IS NOT NULL AND length(dmnd_hash_vl::text) <> 64) THEN
        RAISE EXCEPTION 'Hash length must be 64 before upgrade: integration.candidate_application.dmnd_hash_vl' USING ERRCODE='22000';
    END IF;
    IF EXISTS (SELECT 1 FROM integration.source_metadata WHERE sou_hash_vl IS NOT NULL AND length(sou_hash_vl::text) <> 64) THEN
        RAISE EXCEPTION 'Hash length must be 64 before upgrade: integration.source_metadata.sou_hash_vl' USING ERRCODE='22000';
    END IF;
    IF EXISTS (SELECT 1 FROM integration.sync_item WHERE sou_hash_vl IS NOT NULL AND length(sou_hash_vl::text) <> 64) THEN
        RAISE EXCEPTION 'Hash length must be 64 before upgrade: integration.sync_item.sou_hash_vl' USING ERRCODE='22000';
    END IF;
    IF EXISTS (SELECT 1 FROM integration.sync_run WHERE idmpt_key_hash_vl IS NOT NULL AND length(idmpt_key_hash_vl::text) <> 64) THEN
        RAISE EXCEPTION 'Hash length must be 64 before upgrade: integration.sync_run.idmpt_key_hash_vl' USING ERRCODE='22000';
    END IF;
    IF EXISTS (SELECT 1 FROM integration.sync_run WHERE dmnd_hash_vl IS NOT NULL AND length(dmnd_hash_vl::text) <> 64) THEN
        RAISE EXCEPTION 'Hash length must be 64 before upgrade: integration.sync_run.dmnd_hash_vl' USING ERRCODE='22000';
    END IF;
END;
$audit_hashes$;

ALTER TABLE analytics.search_result RENAME COLUMN dataset_id TO dtset_id;
ALTER TABLE analytics.search_result RENAME COLUMN publication_no TO pstg_no;
ALTER TABLE integration.auto_publication_task RENAME COLUMN source_hash TO sou_hash_vl;
ALTER TABLE integration.auto_publication_task RENAME COLUMN error_code TO err_cd;
ALTER TABLE integration.auto_publication_task RENAME COLUMN created_at TO crt_dt;
ALTER TABLE integration.publication_batch RENAME COLUMN actor_id TO prcs_user_id;
ALTER TABLE integration.publication_batch RENAME COLUMN key_hash TO idmpt_key_hash_vl;
ALTER TABLE integration.publication_batch RENAME COLUMN request_hash TO dmnd_hash_vl;
ALTER TABLE integration.publication_batch RENAME COLUMN created_at TO crt_dt;
ALTER TABLE integration.publication_policy RENAME COLUMN actor_id TO prcs_user_id;
ALTER TABLE integration.publication_policy RENAME COLUMN lock_version TO lck_ver_no;
ALTER TABLE integration.publication_policy RENAME COLUMN updated_at TO mdfcn_dt;
ALTER TABLE integration.publication_result RENAME COLUMN created_at TO crt_dt;
ALTER TABLE delivery.dataset_data_access RENAME COLUMN dataset_id TO dtset_id;
ALTER TABLE delivery.dataset_data_access RENAME COLUMN connection_id TO sou_lnkg_id;
ALTER TABLE delivery.dataset_data_access RENAME COLUMN enabled TO use_yn;
ALTER TABLE delivery.dataset_data_access RENAME COLUMN mode TO inq_mode_cd;
ALTER TABLE delivery.dataset_data_access RENAME COLUMN query_path TO inq_path;
ALTER TABLE delivery.dataset_data_access RENAME COLUMN scope_field TO fltr_fld_nm;
ALTER TABLE delivery.dataset_data_access RENAME COLUMN scope_value TO fltr_vl;
ALTER TABLE delivery.dataset_data_access RENAME COLUMN scope_parameter TO fltr_param_nm;
ALTER TABLE delivery.dataset_data_access RENAME COLUMN fixed_query TO fix_inq_cn;
ALTER TABLE delivery.dataset_data_access RENAME COLUMN lock_version TO lck_ver_no;
ALTER TABLE delivery.dataset_data_access RENAME COLUMN checked_connection_version TO insp_lnkg_ver_no;
ALTER TABLE delivery.dataset_data_access RENAME COLUMN checked_at TO insp_dt;
ALTER TABLE delivery.dataset_data_access RENAME COLUMN updated_by TO mdfr_id;
ALTER TABLE delivery.dataset_data_access RENAME COLUMN updated_at TO mdfcn_dt;
ALTER TABLE delivery.data_access_event RENAME COLUMN dataset_id TO dtset_id;
ALTER TABLE delivery.data_access_event RENAME COLUMN actor_id TO prcs_user_id;
ALTER TABLE delivery.data_access_event RENAME COLUMN action TO prcs_cd;
ALTER TABLE delivery.data_access_event RENAME COLUMN configuration_version TO stng_ver_no;
ALTER TABLE delivery.data_access_event RENAME COLUMN occurred_at TO ocrn_dt;
ALTER TABLE analytics.search_event RENAME COLUMN expires_at TO expry_dt;
ALTER TABLE analytics.search_event RENAME COLUMN feedback_token_hash TO feedback_tkn_hash_vl;
ALTER TABLE analytics.search_event RENAME COLUMN returned_size TO returned_page_sz;
ALTER TABLE analytics.search_feedback RENAME COLUMN search_event_id TO srch_event_id;
ALTER TABLE analytics.search_feedback RENAME COLUMN rating TO srch_feedback_cd;
ALTER TABLE analytics.search_feedback RENAME COLUMN rated_at TO feedback_dt;
ALTER TABLE analytics.search_result RENAME COLUMN search_event_id TO srch_event_id;
ALTER TABLE analytics.search_result RENAME COLUMN ordinal TO seq_no;
ALTER TABLE integration.auto_publication_task RENAME COLUMN connection_id TO lnkg_id;
ALTER TABLE integration.auto_publication_task RENAME COLUMN candidate_id TO sou_cand_id;
ALTER TABLE integration.auto_publication_task RENAME COLUMN policy_version TO pstg_stng_ver_no;
ALTER TABLE integration.auto_publication_task RENAME COLUMN connection_version TO lnkg_stng_ver_no;
ALTER TABLE integration.auto_publication_task RENAME COLUMN status TO stts_cd;
ALTER TABLE integration.publication_batch RENAME COLUMN connection_id TO lnkg_id;
ALTER TABLE integration.publication_batch RENAME COLUMN policy_snapshot TO pstg_stng_cn;
ALTER TABLE integration.publication_policy RENAME COLUMN connection_id TO lnkg_id;
ALTER TABLE integration.publication_policy RENAME COLUMN default_category_id TO default_clsf_id;
ALTER TABLE integration.publication_result RENAME COLUMN candidate_id TO sou_cand_id;
ALTER TABLE integration.publication_result RENAME COLUMN result TO pstg_rslt_cn;
ALTER TABLE integration.sync_run RENAME COLUMN tot_itm_cnt TO tot_artcl_cnt;
ALTER TABLE integration.sync_run RENAME COLUMN prcs_itm_cnt TO prcs_artcl_cnt;
ALTER TABLE integration.sync_run RENAME COLUMN save_itm_cnt TO save_artcl_cnt;
ALTER TABLE integration.sync_run RENAME COLUMN unchg_itm_cnt TO unchg_artcl_cnt;
ALTER TABLE integration.sync_run RENAME COLUMN fail_itm_cnt TO fail_artcl_cnt;

-- PostgreSQL requires UPDATE OF triggers to be recreated around an affected type change.
DROP TRIGGER organization_local_auth_revoke ON identity.organization;
DROP TRIGGER term_review_contract ON standard.dictionary_entry;
ALTER TABLE analytics.analysis_run ALTER COLUMN stts_cd TYPE character varying(30);
ALTER TABLE analytics.report ALTER COLUMN type_cd TYPE character varying(30);
ALTER TABLE catalog.dataset_revision ALTER COLUMN stts_cd TYPE character varying(30);
ALTER TABLE catalog.discovery_lookup ALTER COLUMN type_cd TYPE character varying(30);
ALTER TABLE catalog.discovery_lookup ALTER COLUMN cd TYPE character varying(100);
ALTER TABLE catalog.publication ALTER COLUMN stts_cd TYPE character varying(30);
ALTER TABLE catalog.revision_discovery ALTER COLUMN type_cd TYPE character varying(30);
ALTER TABLE classification.category ALTER COLUMN cd TYPE character varying(100);
ALTER TABLE classification.category ALTER COLUMN expln TYPE text;
ALTER TABLE delivery.data_access_event ALTER COLUMN prcs_cd TYPE character varying(80);
ALTER TABLE delivery.data_service_version ALTER COLUMN stts_cd TYPE character varying(30);
ALTER TABLE identity.local_account ALTER COLUMN stts_cd TYPE character varying(30);
ALTER TABLE identity.organization ALTER COLUMN cd TYPE character varying(100);
ALTER TABLE identity.organization ALTER COLUMN stts_cd TYPE character varying(30);
ALTER TABLE integration.auto_publication_task ALTER COLUMN stts_cd TYPE character varying(30);
ALTER TABLE integration.candidate_application ALTER COLUMN sou_hash_vl TYPE character varying(64);
ALTER TABLE integration.candidate_application ALTER COLUMN dmnd_id TYPE character varying(100);
ALTER TABLE integration.candidate_application ALTER COLUMN idmpt_key_hash_vl TYPE character varying(64);
ALTER TABLE integration.candidate_application ALTER COLUMN dmnd_hash_vl TYPE character varying(64);
ALTER TABLE integration.connection_event ALTER COLUMN prcs_cd TYPE character varying(80);
ALTER TABLE integration.connection_event ALTER COLUMN dmnd_id TYPE character varying(100);
ALTER TABLE integration.source_metadata ALTER COLUMN sou_hash_vl TYPE character varying(64);
ALTER TABLE integration.sync_item ALTER COLUMN sou_hash_vl TYPE character varying(64);
ALTER TABLE integration.sync_item ALTER COLUMN stts_cd TYPE character varying(30);
ALTER TABLE integration.sync_run ALTER COLUMN type_cd TYPE character varying(30);
ALTER TABLE integration.sync_run ALTER COLUMN idmpt_key_hash_vl TYPE character varying(64);
ALTER TABLE integration.sync_run ALTER COLUMN dmnd_hash_vl TYPE character varying(64);
ALTER TABLE metadata.review_request ALTER COLUMN stts_cd TYPE character varying(30);
ALTER TABLE standard.definition_import ALTER COLUMN stts_cd TYPE character varying(30);
ALTER TABLE standard.dictionary ALTER COLUMN cd TYPE character varying(100);
ALTER TABLE standard.dictionary_entry ALTER COLUMN type_cd TYPE character varying(30);
ALTER TABLE standard.dictionary_entry ALTER COLUMN stts_cd TYPE character varying(30);
ALTER TABLE standard.dictionary_export ALTER COLUMN type_cd TYPE character varying(30);
CREATE TRIGGER organization_local_auth_revoke AFTER UPDATE OF stts_cd ON identity.organization
    FOR EACH ROW EXECUTE FUNCTION identity.revoke_local_institution_sessions();
CREATE TRIGGER term_review_contract BEFORE INSERT OR UPDATE OF stts_cd ON standard.dictionary_entry
    FOR EACH ROW EXECUTE FUNCTION standard.validate_term_review();
-- Keep the existing classification description limit while sharing the text storage domain.
ALTER TABLE classification.category ADD CONSTRAINT category_description_length_check CHECK (char_length(expln) <= 4000);

COMMENT ON COLUMN analytics.search_result.dtset_id IS '데이터셋아이디';
COMMENT ON COLUMN analytics.search_result.pstg_no IS '게시번호';
COMMENT ON COLUMN integration.auto_publication_task.sou_hash_vl IS '수집해시값';
COMMENT ON COLUMN integration.auto_publication_task.err_cd IS '오류코드';
COMMENT ON COLUMN integration.auto_publication_task.crt_dt IS '생성일시';
COMMENT ON COLUMN integration.publication_batch.prcs_user_id IS '처리사용자아이디';
COMMENT ON COLUMN integration.publication_batch.idmpt_key_hash_vl IS '멱등키해시값';
COMMENT ON COLUMN integration.publication_batch.dmnd_hash_vl IS '요청해시값';
COMMENT ON COLUMN integration.publication_batch.crt_dt IS '생성일시';
COMMENT ON COLUMN integration.publication_policy.prcs_user_id IS '처리사용자아이디';
COMMENT ON COLUMN integration.publication_policy.lck_ver_no IS '잠금버전번호';
COMMENT ON COLUMN integration.publication_policy.mdfcn_dt IS '수정일시';
COMMENT ON COLUMN integration.publication_result.crt_dt IS '생성일시';
COMMENT ON COLUMN delivery.dataset_data_access.dtset_id IS '데이터셋아이디';
COMMENT ON COLUMN delivery.dataset_data_access.sou_lnkg_id IS '수집연결아이디';
COMMENT ON COLUMN delivery.dataset_data_access.use_yn IS '사용여부';
COMMENT ON COLUMN delivery.dataset_data_access.inq_mode_cd IS '조회방식코드';
COMMENT ON COLUMN delivery.dataset_data_access.inq_path IS '조회경로';
COMMENT ON COLUMN delivery.dataset_data_access.fltr_fld_nm IS '필터필드명';
COMMENT ON COLUMN delivery.dataset_data_access.fltr_vl IS '필터값';
COMMENT ON COLUMN delivery.dataset_data_access.fltr_param_nm IS '필터매개변수명';
COMMENT ON COLUMN delivery.dataset_data_access.fix_inq_cn IS '고정조회내용';
COMMENT ON COLUMN delivery.dataset_data_access.lck_ver_no IS '잠금버전번호';
COMMENT ON COLUMN delivery.dataset_data_access.insp_lnkg_ver_no IS '검사연결버전번호';
COMMENT ON COLUMN delivery.dataset_data_access.insp_dt IS '검사일시';
COMMENT ON COLUMN delivery.dataset_data_access.mdfr_id IS '수정자아이디';
COMMENT ON COLUMN delivery.dataset_data_access.mdfcn_dt IS '수정일시';
COMMENT ON COLUMN delivery.data_access_event.dtset_id IS '데이터셋아이디';
COMMENT ON COLUMN delivery.data_access_event.prcs_user_id IS '처리사용자아이디';
COMMENT ON COLUMN delivery.data_access_event.prcs_cd IS '처리코드';
COMMENT ON COLUMN delivery.data_access_event.stng_ver_no IS '설정버전번호';
COMMENT ON COLUMN delivery.data_access_event.ocrn_dt IS '발생일시';
COMMENT ON COLUMN analytics.search_event.expry_dt IS '만료일시';
COMMENT ON COLUMN analytics.search_event.feedback_tkn_hash_vl IS '평가토큰해시값';
COMMENT ON COLUMN analytics.search_event.returned_page_sz IS '응답페이지크기';
COMMENT ON COLUMN analytics.search_feedback.srch_event_id IS '검색이벤트아이디';
COMMENT ON COLUMN analytics.search_feedback.srch_feedback_cd IS '검색평가코드';
COMMENT ON COLUMN analytics.search_feedback.feedback_dt IS '평가일시';
COMMENT ON COLUMN analytics.search_result.srch_event_id IS '검색이벤트아이디';
COMMENT ON COLUMN analytics.search_result.seq_no IS '순서번호';
COMMENT ON COLUMN integration.auto_publication_task.lnkg_id IS '연결아이디';
COMMENT ON COLUMN integration.auto_publication_task.sou_cand_id IS '수집후보아이디';
COMMENT ON COLUMN integration.auto_publication_task.pstg_stng_ver_no IS '게시설정버전번호';
COMMENT ON COLUMN integration.auto_publication_task.lnkg_stng_ver_no IS '연결설정버전번호';
COMMENT ON COLUMN integration.auto_publication_task.stts_cd IS '상태코드';
COMMENT ON COLUMN integration.publication_batch.lnkg_id IS '연결아이디';
COMMENT ON COLUMN integration.publication_batch.pstg_stng_cn IS '게시설정내용';
COMMENT ON COLUMN integration.publication_policy.lnkg_id IS '연결아이디';
COMMENT ON COLUMN integration.publication_policy.default_clsf_id IS '기본분류아이디';
COMMENT ON COLUMN integration.publication_result.sou_cand_id IS '수집후보아이디';
COMMENT ON COLUMN integration.publication_result.pstg_rslt_cn IS '게시결과내용';
COMMENT ON COLUMN integration.sync_run.tot_artcl_cnt IS '전체항목수';
COMMENT ON COLUMN integration.sync_run.prcs_artcl_cnt IS '처리항목수';
COMMENT ON COLUMN integration.sync_run.save_artcl_cnt IS '저장항목수';
COMMENT ON COLUMN integration.sync_run.unchg_artcl_cnt IS '미변경항목수';
COMMENT ON COLUMN integration.sync_run.fail_artcl_cnt IS '실패항목수';
COMMENT ON COLUMN delivery.data_access_event.id IS '아이디';
COMMENT ON COLUMN integration.sync_run.phase_cd IS '처리단계코드';
COMMENT ON COLUMN integration.sync_run.prgrs_dt IS '진행일시';
