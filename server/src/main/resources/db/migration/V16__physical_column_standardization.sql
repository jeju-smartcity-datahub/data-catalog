-- V16: reviewed physical-column standardization; API names and JSON values are unchanged.
-- Source definitions: supplied JJ_PD_IP_D10 plus separately identified project additions.
-- Existing data types, nullability, defaults, PK/FK/check/index names and object identities are preserved.
-- Workflow dictionary data is not imported or automatically approved by this migration.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';

-- analytics.analysis_run
ALTER TABLE "analytics"."analysis_run" RENAME COLUMN "organization_id" TO "inst_id";
ALTER TABLE "analytics"."analysis_run" RENAME COLUMN "dataset_revision_id" TO "dtset_rvsn_id";
ALTER TABLE "analytics"."analysis_run" RENAME COLUMN "kind" TO "type_cd";
ALTER TABLE "analytics"."analysis_run" RENAME COLUMN "engine_version" TO "engn_ver_nm";
ALTER TABLE "analytics"."analysis_run" RENAME COLUMN "requested_by" TO "rqstr_id";
ALTER TABLE "analytics"."analysis_run" RENAME COLUMN "started_at" TO "bgng_dt";
ALTER TABLE "analytics"."analysis_run" RENAME COLUMN "finished_at" TO "end_dt";
ALTER TABLE "analytics"."analysis_run" RENAME COLUMN "status" TO "stts_cd";
ALTER TABLE "analytics"."analysis_run" RENAME COLUMN "field_count" TO "data_artcl_cnt";
ALTER TABLE "analytics"."analysis_run" RENAME COLUMN "source_hash" TO "sou_hash_vl";
ALTER TABLE "analytics"."analysis_run" RENAME COLUMN "definition_import_id" TO "dfn_inpt_id";
ALTER TABLE "analytics"."analysis_run" RENAME COLUMN "evidence" TO "bss_cn";
ALTER TABLE "analytics"."analysis_run" RENAME COLUMN "error_code" TO "err_cd";
COMMENT ON TABLE "analytics"."analysis_run" IS '메타데이터 분석 이력. 정의서 추출·입력·필드 매핑 실행과 불변 근거를 기록한다.';
COMMENT ON COLUMN "analytics"."analysis_run"."id" IS '메타데이터 분석 이력의 아이디.';
COMMENT ON COLUMN "analytics"."analysis_run"."inst_id" IS '메타데이터 분석 이력의 기관아이디. identity.organization 참조.';
COMMENT ON COLUMN "analytics"."analysis_run"."dtset_rvsn_id" IS '메타데이터 분석 이력의 데이터셋개정아이디. catalog.dataset_revision 참조.';
COMMENT ON COLUMN "analytics"."analysis_run"."type_cd" IS '메타데이터 분석 이력의 유형코드. 허용 코드: DEFINITION_EXPORT, FIELD_MAPPING, DEFINITION_IMPORT.';
COMMENT ON COLUMN "analytics"."analysis_run"."engn_ver_nm" IS '메타데이터 분석 이력의 엔진버전명.';
COMMENT ON COLUMN "analytics"."analysis_run"."rqstr_id" IS '메타데이터 분석 이력의 요청자아이디.';
COMMENT ON COLUMN "analytics"."analysis_run"."bgng_dt" IS '메타데이터 분석 이력의 시작일시.';
COMMENT ON COLUMN "analytics"."analysis_run"."end_dt" IS '메타데이터 분석 이력의 종료일시.';
COMMENT ON COLUMN "analytics"."analysis_run"."stts_cd" IS '메타데이터 분석 이력의 상태코드. 허용 코드: SUCCEEDED, FAILED.';
COMMENT ON COLUMN "analytics"."analysis_run"."data_artcl_cnt" IS '메타데이터 분석 이력의 자료항목수.';
COMMENT ON COLUMN "analytics"."analysis_run"."sou_hash_vl" IS '메타데이터 분석 이력의 원천해시값.';
COMMENT ON COLUMN "analytics"."analysis_run"."dfn_inpt_id" IS '메타데이터 분석 이력의 정의입력아이디. standard.definition_import 참조.';
COMMENT ON COLUMN "analytics"."analysis_run"."bss_cn" IS '메타데이터 분석 이력의 근거내용.';
COMMENT ON COLUMN "analytics"."analysis_run"."err_cd" IS '메타데이터 분석 이력의 오류코드.';

-- analytics.report
ALTER TABLE "analytics"."report" RENAME COLUMN "requested_by" TO "rqstr_id";
ALTER TABLE "analytics"."report" RENAME COLUMN "organization_id" TO "inst_id";
ALTER TABLE "analytics"."report" RENAME COLUMN "kind" TO "type_cd";
ALTER TABLE "analytics"."report" RENAME COLUMN "format" TO "file_fmt_cd";
ALTER TABLE "analytics"."report" RENAME COLUMN "filters" TO "inq_cnd_cn";
ALTER TABLE "analytics"."report" RENAME COLUMN "cutoff" TO "crtr_dt";
ALTER TABLE "analytics"."report" RENAME COLUMN "row_count" TO "row_cnt";
ALTER TABLE "analytics"."report" RENAME COLUMN "key_digest" TO "key_hash_vl";
ALTER TABLE "analytics"."report" RENAME COLUMN "request_hash" TO "dmnd_hash_vl";
ALTER TABLE "analytics"."report" RENAME COLUMN "content_hash" TO "cn_hash_vl";
ALTER TABLE "analytics"."report" RENAME COLUMN "file_content" TO "file_cn";
ALTER TABLE "analytics"."report" RENAME COLUMN "created_at" TO "crt_dt";
ALTER TABLE "analytics"."report" RENAME COLUMN "expires_at" TO "expry_dt";
COMMENT ON TABLE "analytics"."report" IS '검색분석 보고서. 권한 범위와 조회 조건에 따라 생성한 보고서 파일과 만료 정보를 보관한다.';
COMMENT ON COLUMN "analytics"."report"."id" IS '검색분석 보고서의 아이디.';
COMMENT ON COLUMN "analytics"."report"."rqstr_id" IS '검색분석 보고서의 요청자아이디.';
COMMENT ON COLUMN "analytics"."report"."inst_id" IS '검색분석 보고서의 기관아이디. identity.organization 참조.';
COMMENT ON COLUMN "analytics"."report"."type_cd" IS '검색분석 보고서의 유형코드. 허용 코드: SEARCH, ANALYSIS.';
COMMENT ON COLUMN "analytics"."report"."file_fmt_cd" IS '검색분석 보고서의 파일형식코드. 허용 코드: JSON, CSV.';
COMMENT ON COLUMN "analytics"."report"."inq_cnd_cn" IS '검색분석 보고서의 조회조건내용.';
COMMENT ON COLUMN "analytics"."report"."crtr_dt" IS '검색분석 보고서의 기준일시.';
COMMENT ON COLUMN "analytics"."report"."row_cnt" IS '검색분석 보고서의 행수.';
COMMENT ON COLUMN "analytics"."report"."key_hash_vl" IS '검색분석 보고서의 키해시값.';
COMMENT ON COLUMN "analytics"."report"."dmnd_hash_vl" IS '검색분석 보고서의 요청해시값.';
COMMENT ON COLUMN "analytics"."report"."cn_hash_vl" IS '검색분석 보고서의 내용해시값.';
COMMENT ON COLUMN "analytics"."report"."file_cn" IS '검색분석 보고서의 파일내용.';
COMMENT ON COLUMN "analytics"."report"."crt_dt" IS '검색분석 보고서의 생성일시.';
COMMENT ON COLUMN "analytics"."report"."expry_dt" IS '검색분석 보고서의 만료일시.';

-- analytics.search_event
ALTER TABLE "analytics"."search_event" RENAME COLUMN "request_id" TO "dmnd_id";
ALTER TABLE "analytics"."search_event" RENAME COLUMN "occurred_at" TO "ocrn_dt";
ALTER TABLE "analytics"."search_event" RENAME COLUMN "has_query" TO "srch_cn_exst_yn";
ALTER TABLE "analytics"."search_event" RENAME COLUMN "normalized_filters" TO "nrmlz_srch_cnd_cn";
ALTER TABLE "analytics"."search_event" RENAME COLUMN "result_count" TO "rslt_cnt";
ALTER TABLE "analytics"."search_event" RENAME COLUMN "duration_ms" TO "req_ms_cnt";
ALTER TABLE "analytics"."search_event" RENAME COLUMN "search_scope" TO "srch_rng_cd";
COMMENT ON TABLE "analytics"."search_event" IS '공개 검색 이력. 검색어 원문과 IP를 제외한 공개 목록 조회 조건·결과수·시간을 기록한다.';
COMMENT ON COLUMN "analytics"."search_event"."id" IS '공개 검색 이력의 아이디.';
COMMENT ON COLUMN "analytics"."search_event"."dmnd_id" IS '공개 검색 이력의 요청아이디.';
COMMENT ON COLUMN "analytics"."search_event"."ocrn_dt" IS '공개 검색 이력의 발생일시.';
COMMENT ON COLUMN "analytics"."search_event"."srch_cn_exst_yn" IS '공개 검색 이력의 검색내용존재여부.';
COMMENT ON COLUMN "analytics"."search_event"."nrmlz_srch_cnd_cn" IS '공개 검색 이력의 정규화검색조건내용.';
COMMENT ON COLUMN "analytics"."search_event"."rslt_cnt" IS '공개 검색 이력의 결과수.';
COMMENT ON COLUMN "analytics"."search_event"."req_ms_cnt" IS '공개 검색 이력의 소요밀리초수. 단위는 밀리초.';
COMMENT ON COLUMN "analytics"."search_event"."srch_rng_cd" IS '공개 검색 이력의 검색범위코드. 허용 코드: PUBLIC.';

-- apidocs.api_document_version
ALTER TABLE "apidocs"."api_document_version" RENAME COLUMN "service_version_id" TO "srvc_ver_id";
ALTER TABLE "apidocs"."api_document_version" RENAME COLUMN "document_revision" TO "doc_rvsn_no";
ALTER TABLE "apidocs"."api_document_version" RENAME COLUMN "document_kind" TO "doc_type_cd";
ALTER TABLE "apidocs"."api_document_version" RENAME COLUMN "openapi_snapshot" TO "oapi_snpst_cn";
ALTER TABLE "apidocs"."api_document_version" RENAME COLUMN "spec_hash" TO "spcfct_hash_vl";
ALTER TABLE "apidocs"."api_document_version" RENAME COLUMN "schema_hash" TO "schm_hash_vl";
ALTER TABLE "apidocs"."api_document_version" RENAME COLUMN "generator_version" TO "crt_ver_nm";
ALTER TABLE "apidocs"."api_document_version" RENAME COLUMN "created_at" TO "crt_dt";
COMMENT ON TABLE "apidocs"."api_document_version" IS '소비자 API 문서 버전. 검증된 제공 계약 버전에서 생성한 OpenAPI 명세와 해시를 보관한다.';
COMMENT ON COLUMN "apidocs"."api_document_version"."id" IS '소비자 API 문서 버전의 아이디.';
COMMENT ON COLUMN "apidocs"."api_document_version"."srvc_ver_id" IS '소비자 API 문서 버전의 서비스버전아이디. delivery.data_service_version 참조.';
COMMENT ON COLUMN "apidocs"."api_document_version"."doc_rvsn_no" IS '소비자 API 문서 버전의 문서개정번호.';
COMMENT ON COLUMN "apidocs"."api_document_version"."doc_type_cd" IS '소비자 API 문서 버전의 문서유형코드. 허용 코드: OPENAPI.';
COMMENT ON COLUMN "apidocs"."api_document_version"."oapi_snpst_cn" IS '소비자 API 문서 버전의 오픈API스냅샷내용. 해당 버전의 보존값으로 원본 내부 키를 변경하지 않는다.';
COMMENT ON COLUMN "apidocs"."api_document_version"."spcfct_hash_vl" IS '소비자 API 문서 버전의 규격해시값.';
COMMENT ON COLUMN "apidocs"."api_document_version"."schm_hash_vl" IS '소비자 API 문서 버전의 스키마해시값.';
COMMENT ON COLUMN "apidocs"."api_document_version"."crt_ver_nm" IS '소비자 API 문서 버전의 생성버전명.';
COMMENT ON COLUMN "apidocs"."api_document_version"."crt_dt" IS '소비자 API 문서 버전의 생성일시.';

-- audit.audit_event
ALTER TABLE "audit"."audit_event" RENAME COLUMN "occurred_at" TO "ocrn_dt";
ALTER TABLE "audit"."audit_event" RENAME COLUMN "actor_user_id" TO "prcs_user_id";
ALTER TABLE "audit"."audit_event" RENAME COLUMN "organization_id" TO "inst_id";
ALTER TABLE "audit"."audit_event" RENAME COLUMN "dataset_id" TO "dtset_id";
ALTER TABLE "audit"."audit_event" RENAME COLUMN "action" TO "prcs_cd";
ALTER TABLE "audit"."audit_event" RENAME COLUMN "target_type" TO "trgt_type_cd";
ALTER TABLE "audit"."audit_event" RENAME COLUMN "target_id" TO "trgt_id";
ALTER TABLE "audit"."audit_event" RENAME COLUMN "request_id" TO "dmnd_id";
ALTER TABLE "audit"."audit_event" RENAME COLUMN "result" TO "rslt_cd";
ALTER TABLE "audit"."audit_event" RENAME COLUMN "change_summary" TO "chg_smry_cn";
COMMENT ON TABLE "audit"."audit_event" IS '업무 감사 이력. 인증된 주체·기관·대상별 업무 변경을 추적한다.';
COMMENT ON COLUMN "audit"."audit_event"."id" IS '업무 감사 이력의 아이디.';
COMMENT ON COLUMN "audit"."audit_event"."ocrn_dt" IS '업무 감사 이력의 발생일시.';
COMMENT ON COLUMN "audit"."audit_event"."prcs_user_id" IS '업무 감사 이력의 처리사용자아이디.';
COMMENT ON COLUMN "audit"."audit_event"."inst_id" IS '업무 감사 이력의 기관아이디. identity.organization 참조.';
COMMENT ON COLUMN "audit"."audit_event"."dtset_id" IS '업무 감사 이력의 데이터셋아이디. catalog.dataset 참조.';
COMMENT ON COLUMN "audit"."audit_event"."prcs_cd" IS '업무 감사 이력의 처리코드.';
COMMENT ON COLUMN "audit"."audit_event"."trgt_type_cd" IS '업무 감사 이력의 대상유형코드.';
COMMENT ON COLUMN "audit"."audit_event"."trgt_id" IS '업무 감사 이력의 대상아이디.';
COMMENT ON COLUMN "audit"."audit_event"."dmnd_id" IS '업무 감사 이력의 요청아이디.';
COMMENT ON COLUMN "audit"."audit_event"."rslt_cd" IS '업무 감사 이력의 결과코드.';
COMMENT ON COLUMN "audit"."audit_event"."chg_smry_cn" IS '업무 감사 이력의 변경요약내용.';

-- catalog.dataset
ALTER TABLE "catalog"."dataset" RENAME COLUMN "owner_organization_id" TO "psn_inst_id";
ALTER TABLE "catalog"."dataset" RENAME COLUMN "slug" TO "slug_nm";
ALTER TABLE "catalog"."dataset" RENAME COLUMN "lifecycle_status" TO "mng_stts_cd";
ALTER TABLE "catalog"."dataset" RENAME COLUMN "current_publication_id" TO "now_pstg_id";
ALTER TABLE "catalog"."dataset" RENAME COLUMN "lock_version" TO "lck_ver_no";
ALTER TABLE "catalog"."dataset" RENAME COLUMN "created_at" TO "crt_dt";
ALTER TABLE "catalog"."dataset" RENAME COLUMN "updated_at" TO "mdfcn_dt";
COMMENT ON TABLE "catalog"."dataset" IS '데이터셋. 데이터셋의 소유 기관·생명주기·현재 게시본 포인터와 잠금 버전을 관리한다.';
COMMENT ON COLUMN "catalog"."dataset"."id" IS '데이터셋의 아이디. catalog.publication 참조.';
COMMENT ON COLUMN "catalog"."dataset"."psn_inst_id" IS '데이터셋의 소유기관아이디. identity.organization 참조.';
COMMENT ON COLUMN "catalog"."dataset"."slug_nm" IS '데이터셋의 슬러그명.';
COMMENT ON COLUMN "catalog"."dataset"."mng_stts_cd" IS '데이터셋의 관리상태코드. 허용 코드: ACTIVE, RETIRED.';
COMMENT ON COLUMN "catalog"."dataset"."now_pstg_id" IS '데이터셋의 현재게시아이디. catalog.publication 참조.';
COMMENT ON COLUMN "catalog"."dataset"."lck_ver_no" IS '데이터셋의 잠금버전번호. 낙관적 잠금 비교용 버전으로 값 변경 의미를 보존한다.';
COMMENT ON COLUMN "catalog"."dataset"."crt_dt" IS '데이터셋의 생성일시.';
COMMENT ON COLUMN "catalog"."dataset"."mdfcn_dt" IS '데이터셋의 수정일시.';

-- catalog.dataset_revision
ALTER TABLE "catalog"."dataset_revision" RENAME COLUMN "dataset_id" TO "dtset_id";
ALTER TABLE "catalog"."dataset_revision" RENAME COLUMN "revision_no" TO "rvsn_no";
ALTER TABLE "catalog"."dataset_revision" RENAME COLUMN "state" TO "stts_cd";
ALTER TABLE "catalog"."dataset_revision" RENAME COLUMN "title" TO "ttl";
ALTER TABLE "catalog"."dataset_revision" RENAME COLUMN "description" TO "expln";
ALTER TABLE "catalog"."dataset_revision" RENAME COLUMN "provider_organization_id" TO "pvsn_inst_id";
ALTER TABLE "catalog"."dataset_revision" RENAME COLUMN "primary_category_id" TO "main_clsf_id";
ALTER TABLE "catalog"."dataset_revision" RENAME COLUMN "visibility" TO "rls_rng_cd";
ALTER TABLE "catalog"."dataset_revision" RENAME COLUMN "metadata" TO "mtdt_cn";
ALTER TABLE "catalog"."dataset_revision" RENAME COLUMN "created_at" TO "crt_dt";
ALTER TABLE "catalog"."dataset_revision" RENAME COLUMN "content_hash" TO "cn_hash_vl";
ALTER TABLE "catalog"."dataset_revision" RENAME COLUMN "created_by" TO "creatr_id";
ALTER TABLE "catalog"."dataset_revision" RENAME COLUMN "updated_by" TO "mdfr_id";
ALTER TABLE "catalog"."dataset_revision" RENAME COLUMN "updated_at" TO "mdfcn_dt";
COMMENT ON TABLE "catalog"."dataset_revision" IS '데이터셋 메타데이터 개정본. 초안과 검토·승인 상태의 메타데이터 개정본을 보관한다.';
COMMENT ON COLUMN "catalog"."dataset_revision"."id" IS '데이터셋 메타데이터 개정본의 아이디.';
COMMENT ON COLUMN "catalog"."dataset_revision"."dtset_id" IS '데이터셋 메타데이터 개정본의 데이터셋아이디. catalog.dataset 참조.';
COMMENT ON COLUMN "catalog"."dataset_revision"."rvsn_no" IS '데이터셋 메타데이터 개정본의 개정번호.';
COMMENT ON COLUMN "catalog"."dataset_revision"."stts_cd" IS '데이터셋 메타데이터 개정본의 상태코드. 허용 코드: DRAFT, IN_REVIEW, APPROVED, REJECTED.';
COMMENT ON COLUMN "catalog"."dataset_revision"."ttl" IS '데이터셋 메타데이터 개정본의 제목.';
COMMENT ON COLUMN "catalog"."dataset_revision"."expln" IS '데이터셋 메타데이터 개정본의 설명.';
COMMENT ON COLUMN "catalog"."dataset_revision"."pvsn_inst_id" IS '데이터셋 메타데이터 개정본의 제공기관아이디. identity.organization 참조.';
COMMENT ON COLUMN "catalog"."dataset_revision"."main_clsf_id" IS '데이터셋 메타데이터 개정본의 주요분류아이디. classification.category 참조.';
COMMENT ON COLUMN "catalog"."dataset_revision"."rls_rng_cd" IS '데이터셋 메타데이터 개정본의 공개범위코드. 허용 코드: PUBLIC, ORGANIZATION, PRIVATE.';
COMMENT ON COLUMN "catalog"."dataset_revision"."mtdt_cn" IS '데이터셋 메타데이터 개정본의 메타데이터내용.';
COMMENT ON COLUMN "catalog"."dataset_revision"."crt_dt" IS '데이터셋 메타데이터 개정본의 생성일시.';
COMMENT ON COLUMN "catalog"."dataset_revision"."cn_hash_vl" IS '데이터셋 메타데이터 개정본의 내용해시값.';
COMMENT ON COLUMN "catalog"."dataset_revision"."creatr_id" IS '데이터셋 메타데이터 개정본의 생성자ID.';
COMMENT ON COLUMN "catalog"."dataset_revision"."mdfr_id" IS '데이터셋 메타데이터 개정본의 수정자ID.';
COMMENT ON COLUMN "catalog"."dataset_revision"."mdfcn_dt" IS '데이터셋 메타데이터 개정본의 수정일시.';

-- catalog.discovery_lookup
ALTER TABLE "catalog"."discovery_lookup" RENAME COLUMN "kind" TO "type_cd";
ALTER TABLE "catalog"."discovery_lookup" RENAME COLUMN "code" TO "cd";
ALTER TABLE "catalog"."discovery_lookup" RENAME COLUMN "name" TO "nm";
ALTER TABLE "catalog"."discovery_lookup" RENAME COLUMN "active" TO "actvtn_yn";
ALTER TABLE "catalog"."discovery_lookup" RENAME COLUMN "lock_version" TO "lck_ver_no";
ALTER TABLE "catalog"."discovery_lookup" RENAME COLUMN "created_at" TO "crt_dt";
ALTER TABLE "catalog"."discovery_lookup" RENAME COLUMN "updated_at" TO "mdfcn_dt";
COMMENT ON TABLE "catalog"."discovery_lookup" IS '공개 탐색 지역허브 분류. 공개 목록에서 사용하는 지역·허브 분류명이며 접속 설정이나 자격증명이 아니다.';
COMMENT ON COLUMN "catalog"."discovery_lookup"."id" IS '공개 탐색 지역허브 분류의 아이디.';
COMMENT ON COLUMN "catalog"."discovery_lookup"."type_cd" IS '공개 탐색 지역허브 분류의 유형코드. 허용 코드: REGION, HUB.';
COMMENT ON COLUMN "catalog"."discovery_lookup"."cd" IS '공개 탐색 지역허브 분류의 코드.';
COMMENT ON COLUMN "catalog"."discovery_lookup"."nm" IS '공개 탐색 지역허브 분류의 명.';
COMMENT ON COLUMN "catalog"."discovery_lookup"."actvtn_yn" IS '공개 탐색 지역허브 분류의 활성여부.';
COMMENT ON COLUMN "catalog"."discovery_lookup"."lck_ver_no" IS '공개 탐색 지역허브 분류의 잠금버전번호. 낙관적 잠금 비교용 버전으로 값 변경 의미를 보존한다.';
COMMENT ON COLUMN "catalog"."discovery_lookup"."crt_dt" IS '공개 탐색 지역허브 분류의 생성일시.';
COMMENT ON COLUMN "catalog"."discovery_lookup"."mdfcn_dt" IS '공개 탐색 지역허브 분류의 수정일시.';

-- catalog.publication
ALTER TABLE "catalog"."publication" RENAME COLUMN "dataset_id" TO "dtset_id";
ALTER TABLE "catalog"."publication" RENAME COLUMN "revision_id" TO "rvsn_id";
ALTER TABLE "catalog"."publication" RENAME COLUMN "publication_no" TO "pstg_no";
ALTER TABLE "catalog"."publication" RENAME COLUMN "state" TO "stts_cd";
ALTER TABLE "catalog"."publication" RENAME COLUMN "access_state" TO "acs_stts_cd";
ALTER TABLE "catalog"."publication" RENAME COLUMN "published_at" TO "pstg_dt";
ALTER TABLE "catalog"."publication" RENAME COLUMN "withdrawn_at" TO "rcntt_dt";
ALTER TABLE "catalog"."publication" RENAME COLUMN "review_decision_id" TO "rvw_dcsn_id";
ALTER TABLE "catalog"."publication" RENAME COLUMN "published_by" TO "pstg_user_id";
ALTER TABLE "catalog"."publication" RENAME COLUMN "lock_version" TO "lck_ver_no";
ALTER TABLE "catalog"."publication" RENAME COLUMN "withdrawal_reason" TO "rcntt_rsn";
COMMENT ON TABLE "catalog"."publication" IS '데이터셋 게시본. 승인된 메타데이터 개정본의 게시·철회와 접근 준비 상태를 기록한다.';
COMMENT ON COLUMN "catalog"."publication"."id" IS '데이터셋 게시본의 아이디.';
COMMENT ON COLUMN "catalog"."publication"."dtset_id" IS '데이터셋 게시본의 데이터셋아이디. catalog.dataset 참조; catalog.dataset_revision 참조.';
COMMENT ON COLUMN "catalog"."publication"."rvsn_id" IS '데이터셋 게시본의 개정아이디. catalog.dataset_revision 참조.';
COMMENT ON COLUMN "catalog"."publication"."pstg_no" IS '데이터셋 게시본의 게시번호.';
COMMENT ON COLUMN "catalog"."publication"."stts_cd" IS '데이터셋 게시본의 상태코드. 허용 코드: PUBLISHED, WITHDRAWN.';
COMMENT ON COLUMN "catalog"."publication"."acs_stts_cd" IS '데이터셋 게시본의 접근상태코드. 허용 코드: METADATA_ONLY, LINK_READY, SERVICE_READY, UNAVAILABLE.';
COMMENT ON COLUMN "catalog"."publication"."pstg_dt" IS '데이터셋 게시본의 게시일시.';
COMMENT ON COLUMN "catalog"."publication"."rcntt_dt" IS '데이터셋 게시본의 철회일시.';
COMMENT ON COLUMN "catalog"."publication"."rvw_dcsn_id" IS '데이터셋 게시본의 검토결정아이디. metadata.review_decision 참조.';
COMMENT ON COLUMN "catalog"."publication"."pstg_user_id" IS '데이터셋 게시본의 게시사용자아이디.';
COMMENT ON COLUMN "catalog"."publication"."lck_ver_no" IS '데이터셋 게시본의 잠금버전번호. 낙관적 잠금 비교용 버전으로 값 변경 의미를 보존한다.';
COMMENT ON COLUMN "catalog"."publication"."rcntt_rsn" IS '데이터셋 게시본의 철회사유.';

-- catalog.revision_discovery
ALTER TABLE "catalog"."revision_discovery" RENAME COLUMN "revision_id" TO "rvsn_id";
ALTER TABLE "catalog"."revision_discovery" RENAME COLUMN "lookup_id" TO "clsf_id";
ALTER TABLE "catalog"."revision_discovery" RENAME COLUMN "kind" TO "type_cd";
ALTER TABLE "catalog"."revision_discovery" RENAME COLUMN "name_snapshot" TO "nm_snpst";
COMMENT ON TABLE "catalog"."revision_discovery" IS '개정본 지역허브 연결. 메타데이터 개정본에 연결된 지역·허브 분류와 당시 이름을 보존한다.';
COMMENT ON COLUMN "catalog"."revision_discovery"."rvsn_id" IS '개정본 지역허브 연결의 개정아이디. catalog.dataset_revision 참조.';
COMMENT ON COLUMN "catalog"."revision_discovery"."clsf_id" IS '개정본 지역허브 연결의 분류아이디. catalog.discovery_lookup 참조.';
COMMENT ON COLUMN "catalog"."revision_discovery"."type_cd" IS '개정본 지역허브 연결의 유형코드. catalog.discovery_lookup 참조.';
COMMENT ON COLUMN "catalog"."revision_discovery"."nm_snpst" IS '개정본 지역허브 연결의 명스냅샷. 해당 버전의 보존값으로 원본 내부 키를 변경하지 않는다.';

-- catalog.revision_topic
ALTER TABLE "catalog"."revision_topic" RENAME COLUMN "revision_id" TO "rvsn_id";
ALTER TABLE "catalog"."revision_topic" RENAME COLUMN "topic_id" TO "tpc_id";
ALTER TABLE "catalog"."revision_topic" RENAME COLUMN "name_snapshot" TO "nm_snpst";
COMMENT ON TABLE "catalog"."revision_topic" IS '개정본 주제 연결. 메타데이터 개정본에 연결된 독립 주제와 당시 이름을 보존한다.';
COMMENT ON COLUMN "catalog"."revision_topic"."rvsn_id" IS '개정본 주제 연결의 개정아이디. catalog.dataset_revision 참조.';
COMMENT ON COLUMN "catalog"."revision_topic"."tpc_id" IS '개정본 주제 연결의 주제아이디. classification.category 참조.';
COMMENT ON COLUMN "catalog"."revision_topic"."nm_snpst" IS '개정본 주제 연결의 명스냅샷. 해당 버전의 보존값으로 원본 내부 키를 변경하지 않는다.';

-- classification.category
ALTER TABLE "classification"."category" RENAME COLUMN "parent_id" TO "up_id";
ALTER TABLE "classification"."category" RENAME COLUMN "code" TO "cd";
ALTER TABLE "classification"."category" RENAME COLUMN "name" TO "nm";
ALTER TABLE "classification"."category" RENAME COLUMN "active" TO "actvtn_yn";
ALTER TABLE "classification"."category" RENAME COLUMN "ordinal" TO "sort_seq";
ALTER TABLE "classification"."category" RENAME COLUMN "lock_version" TO "lck_ver_no";
ALTER TABLE "classification"."category" RENAME COLUMN "created_at" TO "crt_dt";
ALTER TABLE "classification"."category" RENAME COLUMN "updated_at" TO "mdfcn_dt";
ALTER TABLE "classification"."category" RENAME COLUMN "description" TO "expln";
COMMENT ON TABLE "classification"."category" IS '분야주제 분류. 계층형 분야·주제 코드와 설명·사용 상태를 관리한다.';
COMMENT ON COLUMN "classification"."category"."id" IS '분야주제 분류의 아이디.';
COMMENT ON COLUMN "classification"."category"."up_id" IS '분야주제 분류의 상위아이디. classification.category 참조.';
COMMENT ON COLUMN "classification"."category"."cd" IS '분야주제 분류의 코드.';
COMMENT ON COLUMN "classification"."category"."nm" IS '분야주제 분류의 명.';
COMMENT ON COLUMN "classification"."category"."actvtn_yn" IS '분야주제 분류의 활성여부.';
COMMENT ON COLUMN "classification"."category"."sort_seq" IS '분야주제 분류의 정렬순서.';
COMMENT ON COLUMN "classification"."category"."lck_ver_no" IS '분야주제 분류의 잠금버전번호. 낙관적 잠금 비교용 버전으로 값 변경 의미를 보존한다.';
COMMENT ON COLUMN "classification"."category"."crt_dt" IS '분야주제 분류의 생성일시.';
COMMENT ON COLUMN "classification"."category"."mdfcn_dt" IS '분야주제 분류의 수정일시.';
COMMENT ON COLUMN "classification"."category"."expln" IS '분야주제 분류의 설명.';

-- delivery.data_service_version
ALTER TABLE "delivery"."data_service_version" RENAME COLUMN "dataset_id" TO "dtset_id";
ALTER TABLE "delivery"."data_service_version" RENAME COLUMN "contract_version" TO "ctrt_ver_no";
ALTER TABLE "delivery"."data_service_version" RENAME COLUMN "delivery_mode" TO "pvsn_type_cd";
ALTER TABLE "delivery"."data_service_version" RENAME COLUMN "access_policy" TO "acs_plcy_cd";
ALTER TABLE "delivery"."data_service_version" RENAME COLUMN "state" TO "stts_cd";
ALTER TABLE "delivery"."data_service_version" RENAME COLUMN "verification_level" TO "vrfc_lvl_cd";
ALTER TABLE "delivery"."data_service_version" RENAME COLUMN "schema_snapshot" TO "schm_snpst_cn";
ALTER TABLE "delivery"."data_service_version" RENAME COLUMN "schema_hash" TO "schm_hash_vl";
ALTER TABLE "delivery"."data_service_version" RENAME COLUMN "contract_snapshot" TO "ctrt_snpst_cn";
ALTER TABLE "delivery"."data_service_version" RENAME COLUMN "contract_hash" TO "ctrt_hash_vl";
ALTER TABLE "delivery"."data_service_version" RENAME COLUMN "created_at" TO "crt_dt";
COMMENT ON TABLE "delivery"."data_service_version" IS '데이터 제공 계약 버전. 제공 방식·접근 정책·검증 수준·스키마와 계약의 불변 버전을 관리한다.';
COMMENT ON COLUMN "delivery"."data_service_version"."id" IS '데이터 제공 계약 버전의 아이디.';
COMMENT ON COLUMN "delivery"."data_service_version"."dtset_id" IS '데이터 제공 계약 버전의 데이터셋아이디. catalog.dataset 참조.';
COMMENT ON COLUMN "delivery"."data_service_version"."ctrt_ver_no" IS '데이터 제공 계약 버전의 계약버전번호.';
COMMENT ON COLUMN "delivery"."data_service_version"."pvsn_type_cd" IS '데이터 제공 계약 버전의 제공유형코드. 허용 코드: LINK, PROXY, MATERIALIZED.';
COMMENT ON COLUMN "delivery"."data_service_version"."acs_plcy_cd" IS '데이터 제공 계약 버전의 접근정책코드. 허용 코드: PUBLIC, UPSTREAM_AUTH, RESTRICTED.';
COMMENT ON COLUMN "delivery"."data_service_version"."stts_cd" IS '데이터 제공 계약 버전의 상태코드. 허용 코드: DRAFT, VERIFIED, REVOKED.';
COMMENT ON COLUMN "delivery"."data_service_version"."vrfc_lvl_cd" IS '데이터 제공 계약 버전의 검증수준코드. 허용 코드: FIXTURE, REAL_CONTRACT.';
COMMENT ON COLUMN "delivery"."data_service_version"."schm_snpst_cn" IS '데이터 제공 계약 버전의 스키마스냅샷내용. 해당 버전의 보존값으로 원본 내부 키를 변경하지 않는다.';
COMMENT ON COLUMN "delivery"."data_service_version"."schm_hash_vl" IS '데이터 제공 계약 버전의 스키마해시값.';
COMMENT ON COLUMN "delivery"."data_service_version"."ctrt_snpst_cn" IS '데이터 제공 계약 버전의 계약스냅샷내용. 해당 버전의 보존값으로 원본 내부 키를 변경하지 않는다.';
COMMENT ON COLUMN "delivery"."data_service_version"."ctrt_hash_vl" IS '데이터 제공 계약 버전의 계약해시값.';
COMMENT ON COLUMN "delivery"."data_service_version"."crt_dt" IS '데이터 제공 계약 버전의 생성일시.';

-- delivery.publication_service
ALTER TABLE "delivery"."publication_service" RENAME COLUMN "dataset_id" TO "dtset_id";
ALTER TABLE "delivery"."publication_service" RENAME COLUMN "publication_id" TO "pstg_id";
ALTER TABLE "delivery"."publication_service" RENAME COLUMN "service_version_id" TO "srvc_ver_id";
COMMENT ON TABLE "delivery"."publication_service" IS '게시본 제공 계약 연결. 게시본과 제공 서비스 계약 버전을 연결한다.';
COMMENT ON COLUMN "delivery"."publication_service"."dtset_id" IS '게시본 제공 계약 연결의 데이터셋아이디. catalog.publication 참조; delivery.data_service_version 참조.';
COMMENT ON COLUMN "delivery"."publication_service"."pstg_id" IS '게시본 제공 계약 연결의 게시아이디. catalog.publication 참조.';
COMMENT ON COLUMN "delivery"."publication_service"."srvc_ver_id" IS '게시본 제공 계약 연결의 서비스버전아이디. delivery.data_service_version 참조.';

-- identity.organization
ALTER TABLE "identity"."organization" RENAME COLUMN "code" TO "cd";
ALTER TABLE "identity"."organization" RENAME COLUMN "name" TO "inst_nm";
ALTER TABLE "identity"."organization" RENAME COLUMN "status" TO "stts_cd";
ALTER TABLE "identity"."organization" RENAME COLUMN "lock_version" TO "lck_ver_no";
ALTER TABLE "identity"."organization" RENAME COLUMN "created_at" TO "crt_dt";
ALTER TABLE "identity"."organization" RENAME COLUMN "updated_at" TO "mdfcn_dt";
COMMENT ON TABLE "identity"."organization" IS '기관. 데이터셋 소유·제공 및 기관 권한 범위의 기관을 관리한다.';
COMMENT ON COLUMN "identity"."organization"."id" IS '기관의 아이디.';
COMMENT ON COLUMN "identity"."organization"."cd" IS '기관의 코드.';
COMMENT ON COLUMN "identity"."organization"."inst_nm" IS '기관의 기관명.';
COMMENT ON COLUMN "identity"."organization"."stts_cd" IS '기관의 상태코드. 허용 코드: ACTIVE, INACTIVE.';
COMMENT ON COLUMN "identity"."organization"."lck_ver_no" IS '기관의 잠금버전번호. 낙관적 잠금 비교용 버전으로 값 변경 의미를 보존한다.';
COMMENT ON COLUMN "identity"."organization"."crt_dt" IS '기관의 생성일시.';
COMMENT ON COLUMN "identity"."organization"."mdfcn_dt" IS '기관의 수정일시.';

-- integration.candidate_application
ALTER TABLE "integration"."candidate_application" RENAME COLUMN "source_metadata_id" TO "sou_mtdt_id";
ALTER TABLE "integration"."candidate_application" RENAME COLUMN "source_revision_no" TO "sou_rvsn_no";
ALTER TABLE "integration"."candidate_application" RENAME COLUMN "source_hash" TO "sou_hash_vl";
ALTER TABLE "integration"."candidate_application" RENAME COLUMN "dataset_id" TO "dtset_id";
ALTER TABLE "integration"."candidate_application" RENAME COLUMN "dataset_revision_id" TO "dtset_rvsn_id";
ALTER TABLE "integration"."candidate_application" RENAME COLUMN "dataset_lock_version" TO "dtset_lck_ver_no";
ALTER TABLE "integration"."candidate_application" RENAME COLUMN "actor_id" TO "prcs_user_id";
ALTER TABLE "integration"."candidate_application" RENAME COLUMN "request_id" TO "dmnd_id";
ALTER TABLE "integration"."candidate_application" RENAME COLUMN "idempotency_digest" TO "idmpt_key_hash_vl";
ALTER TABLE "integration"."candidate_application" RENAME COLUMN "request_hash" TO "dmnd_hash_vl";
ALTER TABLE "integration"."candidate_application" RENAME COLUMN "applied_at" TO "aplcn_dt";
COMMENT ON TABLE "integration"."candidate_application" IS '수집 후보 적용 이력. 원천 메타데이터 후보를 데이터셋 초안에 적용한 버전과 멱등 결과를 기록한다.';
COMMENT ON COLUMN "integration"."candidate_application"."id" IS '수집 후보 적용 이력의 아이디.';
COMMENT ON COLUMN "integration"."candidate_application"."sou_mtdt_id" IS '수집 후보 적용 이력의 원천메타데이터아이디. integration.source_metadata 참조.';
COMMENT ON COLUMN "integration"."candidate_application"."sou_rvsn_no" IS '수집 후보 적용 이력의 원천개정번호.';
COMMENT ON COLUMN "integration"."candidate_application"."sou_hash_vl" IS '수집 후보 적용 이력의 원천해시값.';
COMMENT ON COLUMN "integration"."candidate_application"."dtset_id" IS '수집 후보 적용 이력의 데이터셋아이디. catalog.dataset 참조.';
COMMENT ON COLUMN "integration"."candidate_application"."dtset_rvsn_id" IS '수집 후보 적용 이력의 데이터셋개정아이디. catalog.dataset_revision 참조.';
COMMENT ON COLUMN "integration"."candidate_application"."dtset_lck_ver_no" IS '수집 후보 적용 이력의 데이터셋잠금버전번호. 낙관적 잠금 비교용 버전으로 값 변경 의미를 보존한다.';
COMMENT ON COLUMN "integration"."candidate_application"."prcs_user_id" IS '수집 후보 적용 이력의 처리사용자아이디.';
COMMENT ON COLUMN "integration"."candidate_application"."dmnd_id" IS '수집 후보 적용 이력의 요청아이디.';
COMMENT ON COLUMN "integration"."candidate_application"."idmpt_key_hash_vl" IS '수집 후보 적용 이력의 멱등키해시값.';
COMMENT ON COLUMN "integration"."candidate_application"."dmnd_hash_vl" IS '수집 후보 적용 이력의 요청해시값.';
COMMENT ON COLUMN "integration"."candidate_application"."aplcn_dt" IS '수집 후보 적용 이력의 적용일시.';

-- integration.connection_event
ALTER TABLE "integration"."connection_event" RENAME COLUMN "connection_id" TO "lnkg_id";
ALTER TABLE "integration"."connection_event" RENAME COLUMN "actor_id" TO "prcs_user_id";
ALTER TABLE "integration"."connection_event" RENAME COLUMN "action" TO "prcs_cd";
ALTER TABLE "integration"."connection_event" RENAME COLUMN "request_id" TO "dmnd_id";
ALTER TABLE "integration"."connection_event" RENAME COLUMN "configuration_version" TO "stng_ver_no";
ALTER TABLE "integration"."connection_event" RENAME COLUMN "occurred_at" TO "ocrn_dt";
COMMENT ON TABLE "integration"."connection_event" IS '원천 연결 변경 이력. 연결 설정 변경 주체·동작·버전을 기록한다.';
COMMENT ON COLUMN "integration"."connection_event"."id" IS '원천 연결 변경 이력의 아이디.';
COMMENT ON COLUMN "integration"."connection_event"."lnkg_id" IS '원천 연결 변경 이력의 연결아이디. integration.source_connection 참조.';
COMMENT ON COLUMN "integration"."connection_event"."prcs_user_id" IS '원천 연결 변경 이력의 처리사용자아이디.';
COMMENT ON COLUMN "integration"."connection_event"."prcs_cd" IS '원천 연결 변경 이력의 처리코드.';
COMMENT ON COLUMN "integration"."connection_event"."dmnd_id" IS '원천 연결 변경 이력의 요청아이디.';
COMMENT ON COLUMN "integration"."connection_event"."stng_ver_no" IS '원천 연결 변경 이력의 설정버전번호.';
COMMENT ON COLUMN "integration"."connection_event"."ocrn_dt" IS '원천 연결 변경 이력의 발생일시.';

-- integration.source_connection
ALTER TABLE "integration"."source_connection" RENAME COLUMN "organization_id" TO "inst_id";
ALTER TABLE "integration"."source_connection" RENAME COLUMN "name" TO "nm";
ALTER TABLE "integration"."source_connection" RENAME COLUMN "adapter_code" TO "adptr_cd";
ALTER TABLE "integration"."source_connection" RENAME COLUMN "endpoint" TO "lnkg_addr";
ALTER TABLE "integration"."source_connection" RENAME COLUMN "secret_reference" TO "secrt_rfrnc_nm";
ALTER TABLE "integration"."source_connection" RENAME COLUMN "enabled" TO "use_yn";
ALTER TABLE "integration"."source_connection" RENAME COLUMN "timeout_seconds" TO "lmt_sec_cnt";
ALTER TABLE "integration"."source_connection" RENAME COLUMN "max_attempts" TO "max_prcstry_nmtm";
ALTER TABLE "integration"."source_connection" RENAME COLUMN "max_pages" TO "max_qpage_cnt";
ALTER TABLE "integration"."source_connection" RENAME COLUMN "lock_version" TO "lck_ver_no";
ALTER TABLE "integration"."source_connection" RENAME COLUMN "created_at" TO "crt_dt";
ALTER TABLE "integration"."source_connection" RENAME COLUMN "updated_at" TO "mdfcn_dt";
COMMENT ON TABLE "integration"."source_connection" IS '원천 연결 설정. 기관별 adapter·주소·서버 비밀 참조와 수집 제한을 관리한다.';
COMMENT ON COLUMN "integration"."source_connection"."id" IS '원천 연결 설정의 아이디.';
COMMENT ON COLUMN "integration"."source_connection"."inst_id" IS '원천 연결 설정의 기관아이디. identity.organization 참조.';
COMMENT ON COLUMN "integration"."source_connection"."nm" IS '원천 연결 설정의 명.';
COMMENT ON COLUMN "integration"."source_connection"."adptr_cd" IS '원천 연결 설정의 어댑터코드.';
COMMENT ON COLUMN "integration"."source_connection"."lnkg_addr" IS '원천 연결 설정의 연결주소.';
COMMENT ON COLUMN "integration"."source_connection"."secrt_rfrnc_nm" IS '원천 연결 설정의 비밀참조명. 실제 자격증명이 아닌 서버 비밀 저장소 참조.';
COMMENT ON COLUMN "integration"."source_connection"."use_yn" IS '원천 연결 설정의 사용여부.';
COMMENT ON COLUMN "integration"."source_connection"."lmt_sec_cnt" IS '원천 연결 설정의 제한초수. 원천 호출 제한 시간, 단위는 초.';
COMMENT ON COLUMN "integration"."source_connection"."max_prcstry_nmtm" IS '원천 연결 설정의 최대처리시도횟수.';
COMMENT ON COLUMN "integration"."source_connection"."max_qpage_cnt" IS '원천 연결 설정의 최대조회페이지수.';
COMMENT ON COLUMN "integration"."source_connection"."lck_ver_no" IS '원천 연결 설정의 잠금버전번호. 낙관적 잠금 비교용 버전으로 값 변경 의미를 보존한다.';
COMMENT ON COLUMN "integration"."source_connection"."crt_dt" IS '원천 연결 설정의 생성일시.';
COMMENT ON COLUMN "integration"."source_connection"."mdfcn_dt" IS '원천 연결 설정의 수정일시.';

-- integration.source_metadata
ALTER TABLE "integration"."source_metadata" RENAME COLUMN "source_connection_id" TO "sou_lnkg_id";
ALTER TABLE "integration"."source_metadata" RENAME COLUMN "external_id" TO "otsd_id";
ALTER TABLE "integration"."source_metadata" RENAME COLUMN "source_hash" TO "sou_hash_vl";
ALTER TABLE "integration"."source_metadata" RENAME COLUMN "source_revision" TO "sou_rvsn_nm";
ALTER TABLE "integration"."source_metadata" RENAME COLUMN "candidate" TO "cand_cn";
ALTER TABLE "integration"."source_metadata" RENAME COLUMN "mapping_version" TO "mpng_ver_nm";
ALTER TABLE "integration"."source_metadata" RENAME COLUMN "revision_no" TO "rvsn_no";
ALTER TABLE "integration"."source_metadata" RENAME COLUMN "last_run_id" TO "last_excn_id";
ALTER TABLE "integration"."source_metadata" RENAME COLUMN "updated_at" TO "mdfcn_dt";
ALTER TABLE "integration"."source_metadata" RENAME COLUMN "dataset_id" TO "dtset_id";
ALTER TABLE "integration"."source_metadata" RENAME COLUMN "applied_source_hash" TO "aplcn_sou_hash_vl";
ALTER TABLE "integration"."source_metadata" RENAME COLUMN "applied_title" TO "aplcn_ttl";
ALTER TABLE "integration"."source_metadata" RENAME COLUMN "applied_description" TO "aplcn_expln";
ALTER TABLE "integration"."source_metadata" RENAME COLUMN "application_version" TO "aplcn_ver_no";
ALTER TABLE "integration"."source_metadata" RENAME COLUMN "applied_at" TO "aplcn_dt";
COMMENT ON TABLE "integration"."source_metadata" IS '수집 원천 메타데이터 후보. 원천 식별자별 최신 메타데이터 후보와 마지막 적용 기준을 보관한다.';
COMMENT ON COLUMN "integration"."source_metadata"."id" IS '수집 원천 메타데이터 후보의 아이디.';
COMMENT ON COLUMN "integration"."source_metadata"."sou_lnkg_id" IS '수집 원천 메타데이터 후보의 원천연결아이디. integration.source_connection 참조.';
COMMENT ON COLUMN "integration"."source_metadata"."otsd_id" IS '수집 원천 메타데이터 후보의 외부아이디.';
COMMENT ON COLUMN "integration"."source_metadata"."sou_hash_vl" IS '수집 원천 메타데이터 후보의 원천해시값.';
COMMENT ON COLUMN "integration"."source_metadata"."sou_rvsn_nm" IS '수집 원천 메타데이터 후보의 원천개정명.';
COMMENT ON COLUMN "integration"."source_metadata"."cand_cn" IS '수집 원천 메타데이터 후보의 후보내용.';
COMMENT ON COLUMN "integration"."source_metadata"."mpng_ver_nm" IS '수집 원천 메타데이터 후보의 매핑버전명.';
COMMENT ON COLUMN "integration"."source_metadata"."rvsn_no" IS '수집 원천 메타데이터 후보의 개정번호.';
COMMENT ON COLUMN "integration"."source_metadata"."last_excn_id" IS '수집 원천 메타데이터 후보의 최종실행아이디. integration.sync_run 참조.';
COMMENT ON COLUMN "integration"."source_metadata"."mdfcn_dt" IS '수집 원천 메타데이터 후보의 수정일시.';
COMMENT ON COLUMN "integration"."source_metadata"."dtset_id" IS '수집 원천 메타데이터 후보의 데이터셋아이디. catalog.dataset 참조.';
COMMENT ON COLUMN "integration"."source_metadata"."aplcn_sou_hash_vl" IS '수집 원천 메타데이터 후보의 적용원천해시값.';
COMMENT ON COLUMN "integration"."source_metadata"."aplcn_ttl" IS '수집 원천 메타데이터 후보의 적용제목.';
COMMENT ON COLUMN "integration"."source_metadata"."aplcn_expln" IS '수집 원천 메타데이터 후보의 적용설명.';
COMMENT ON COLUMN "integration"."source_metadata"."aplcn_ver_no" IS '수집 원천 메타데이터 후보의 적용버전번호.';
COMMENT ON COLUMN "integration"."source_metadata"."aplcn_dt" IS '수집 원천 메타데이터 후보의 적용일시.';

-- integration.sync_checkpoint
ALTER TABLE "integration"."sync_checkpoint" RENAME COLUMN "source_connection_id" TO "sou_lnkg_id";
ALTER TABLE "integration"."sync_checkpoint" RENAME COLUMN "cursor" TO "cursr_vl";
ALTER TABLE "integration"."sync_checkpoint" RENAME COLUMN "last_successful_run_id" TO "last_scs_excn_id";
ALTER TABLE "integration"."sync_checkpoint" RENAME COLUMN "lock_version" TO "lck_ver_no";
COMMENT ON TABLE "integration"."sync_checkpoint" IS '수집 동기화 점검점. 연결별 증분 수집 커서와 마지막 성공 실행을 보관한다.';
COMMENT ON COLUMN "integration"."sync_checkpoint"."sou_lnkg_id" IS '수집 동기화 점검점의 원천연결아이디. integration.source_connection 참조.';
COMMENT ON COLUMN "integration"."sync_checkpoint"."cursr_vl" IS '수집 동기화 점검점의 커서값.';
COMMENT ON COLUMN "integration"."sync_checkpoint"."last_scs_excn_id" IS '수집 동기화 점검점의 최종성공실행아이디. integration.sync_run 참조.';
COMMENT ON COLUMN "integration"."sync_checkpoint"."lck_ver_no" IS '수집 동기화 점검점의 잠금버전번호. 낙관적 잠금 비교용 버전으로 값 변경 의미를 보존한다.';

-- integration.sync_item
ALTER TABLE "integration"."sync_item" RENAME COLUMN "sync_run_id" TO "sync_excn_id";
ALTER TABLE "integration"."sync_item" RENAME COLUMN "external_id" TO "otsd_id";
ALTER TABLE "integration"."sync_item" RENAME COLUMN "source_hash" TO "sou_hash_vl";
ALTER TABLE "integration"."sync_item" RENAME COLUMN "status" TO "stts_cd";
ALTER TABLE "integration"."sync_item" RENAME COLUMN "error_code" TO "err_cd";
ALTER TABLE "integration"."sync_item" RENAME COLUMN "attempt" TO "prcstry_nmtm";
ALTER TABLE "integration"."sync_item" RENAME COLUMN "updated_at" TO "mdfcn_dt";
COMMENT ON TABLE "integration"."sync_item" IS '수집 항목 이력. 수집 실행 내 개별 원천 항목의 변경·실패·시도를 기록한다.';
COMMENT ON COLUMN "integration"."sync_item"."id" IS '수집 항목 이력의 아이디.';
COMMENT ON COLUMN "integration"."sync_item"."sync_excn_id" IS '수집 항목 이력의 동기화실행아이디. integration.sync_run 참조.';
COMMENT ON COLUMN "integration"."sync_item"."otsd_id" IS '수집 항목 이력의 외부아이디.';
COMMENT ON COLUMN "integration"."sync_item"."sou_hash_vl" IS '수집 항목 이력의 원천해시값.';
COMMENT ON COLUMN "integration"."sync_item"."stts_cd" IS '수집 항목 이력의 상태코드. 허용 코드: CANDIDATE, UNCHANGED, FAILED.';
COMMENT ON COLUMN "integration"."sync_item"."err_cd" IS '수집 항목 이력의 오류코드.';
COMMENT ON COLUMN "integration"."sync_item"."prcstry_nmtm" IS '수집 항목 이력의 처리시도횟수.';
COMMENT ON COLUMN "integration"."sync_item"."mdfcn_dt" IS '수집 항목 이력의 수정일시.';

-- integration.sync_run
ALTER TABLE "integration"."sync_run" RENAME COLUMN "source_connection_id" TO "sou_lnkg_id";
ALTER TABLE "integration"."sync_run" RENAME COLUMN "requested_by" TO "rqstr_id";
ALTER TABLE "integration"."sync_run" RENAME COLUMN "kind" TO "type_cd";
ALTER TABLE "integration"."sync_run" RENAME COLUMN "status" TO "stts_cd";
ALTER TABLE "integration"."sync_run" RENAME COLUMN "idempotency_digest" TO "idmpt_key_hash_vl";
ALTER TABLE "integration"."sync_run" RENAME COLUMN "request_hash" TO "dmnd_hash_vl";
ALTER TABLE "integration"."sync_run" RENAME COLUMN "configuration_version" TO "stng_ver_no";
ALTER TABLE "integration"."sync_run" RENAME COLUMN "mapping_version" TO "mpng_ver_nm";
ALTER TABLE "integration"."sync_run" RENAME COLUMN "attempt" TO "prcstry_nmtm";
ALTER TABLE "integration"."sync_run" RENAME COLUMN "lease_token" TO "joblse_tkn_id";
ALTER TABLE "integration"."sync_run" RENAME COLUMN "lease_until" TO "joblse_expry_dt";
ALTER TABLE "integration"."sync_run" RENAME COLUMN "available_at" TO "excn_psblty_dt";
ALTER TABLE "integration"."sync_run" RENAME COLUMN "checkpoint_before" TO "bfr_chkpt_vl";
ALTER TABLE "integration"."sync_run" RENAME COLUMN "checkpoint_after" TO "aftr_chkpt_vl";
ALTER TABLE "integration"."sync_run" RENAME COLUMN "error_code" TO "err_cd";
ALTER TABLE "integration"."sync_run" RENAME COLUMN "created_at" TO "crt_dt";
ALTER TABLE "integration"."sync_run" RENAME COLUMN "started_at" TO "bgng_dt";
ALTER TABLE "integration"."sync_run" RENAME COLUMN "finished_at" TO "end_dt";
COMMENT ON TABLE "integration"."sync_run" IS '수집 실행. 연결 확인·동기화 작업의 실행 상태·재시도·점유와 점검점을 관리한다.';
COMMENT ON COLUMN "integration"."sync_run"."id" IS '수집 실행의 아이디.';
COMMENT ON COLUMN "integration"."sync_run"."sou_lnkg_id" IS '수집 실행의 원천연결아이디. integration.source_connection 참조.';
COMMENT ON COLUMN "integration"."sync_run"."rqstr_id" IS '수집 실행의 요청자아이디.';
COMMENT ON COLUMN "integration"."sync_run"."type_cd" IS '수집 실행의 유형코드. 허용 코드: CHECK, SYNC.';
COMMENT ON COLUMN "integration"."sync_run"."stts_cd" IS '수집 실행의 상태코드. 허용 코드: QUEUED, RUNNING, RETRY_WAIT, SUCCEEDED, PARTIAL_FAILED, FAILED.';
COMMENT ON COLUMN "integration"."sync_run"."idmpt_key_hash_vl" IS '수집 실행의 멱등키해시값.';
COMMENT ON COLUMN "integration"."sync_run"."dmnd_hash_vl" IS '수집 실행의 요청해시값.';
COMMENT ON COLUMN "integration"."sync_run"."stng_ver_no" IS '수집 실행의 설정버전번호.';
COMMENT ON COLUMN "integration"."sync_run"."mpng_ver_nm" IS '수집 실행의 매핑버전명.';
COMMENT ON COLUMN "integration"."sync_run"."prcstry_nmtm" IS '수집 실행의 처리시도횟수.';
COMMENT ON COLUMN "integration"."sync_run"."joblse_tkn_id" IS '수집 실행의 작업점유토큰아이디.';
COMMENT ON COLUMN "integration"."sync_run"."joblse_expry_dt" IS '수집 실행의 작업점유만료일시.';
COMMENT ON COLUMN "integration"."sync_run"."excn_psblty_dt" IS '수집 실행의 실행가능일시.';
COMMENT ON COLUMN "integration"."sync_run"."bfr_chkpt_vl" IS '수집 실행의 이전점검점값.';
COMMENT ON COLUMN "integration"."sync_run"."aftr_chkpt_vl" IS '수집 실행의 이후점검점값.';
COMMENT ON COLUMN "integration"."sync_run"."err_cd" IS '수집 실행의 오류코드.';
COMMENT ON COLUMN "integration"."sync_run"."crt_dt" IS '수집 실행의 생성일시.';
COMMENT ON COLUMN "integration"."sync_run"."bgng_dt" IS '수집 실행의 시작일시.';
COMMENT ON COLUMN "integration"."sync_run"."end_dt" IS '수집 실행의 종료일시.';

-- metadata.idempotency_record
ALTER TABLE "metadata"."idempotency_record" RENAME COLUMN "principal_scope" TO "user_id";
ALTER TABLE "metadata"."idempotency_record" RENAME COLUMN "operation" TO "job_nm";
ALTER TABLE "metadata"."idempotency_record" RENAME COLUMN "key_digest" TO "key_hash_vl";
ALTER TABLE "metadata"."idempotency_record" RENAME COLUMN "request_hash" TO "dmnd_hash_vl";
ALTER TABLE "metadata"."idempotency_record" RENAME COLUMN "result" TO "rslt_cn";
ALTER TABLE "metadata"."idempotency_record" RENAME COLUMN "expires_at" TO "expry_dt";
COMMENT ON TABLE "metadata"."idempotency_record" IS '업무 멱등 처리 기록. 사용자·작업·키 해시별 동일 요청의 처리 결과와 만료를 보관한다.';
COMMENT ON COLUMN "metadata"."idempotency_record"."user_id" IS '업무 멱등 처리 기록의 사용자ID. 현재 계약에서 사용자 UUID의 문자열 표현이며 사용자별 멱등 범위에 사용한다.';
COMMENT ON COLUMN "metadata"."idempotency_record"."job_nm" IS '업무 멱등 처리 기록의 작업명.';
COMMENT ON COLUMN "metadata"."idempotency_record"."key_hash_vl" IS '업무 멱등 처리 기록의 키해시값.';
COMMENT ON COLUMN "metadata"."idempotency_record"."dmnd_hash_vl" IS '업무 멱등 처리 기록의 요청해시값.';
COMMENT ON COLUMN "metadata"."idempotency_record"."rslt_cn" IS '업무 멱등 처리 기록의 결과내용.';
COMMENT ON COLUMN "metadata"."idempotency_record"."expry_dt" IS '업무 멱등 처리 기록의 만료일시.';

-- metadata.review_decision
ALTER TABLE "metadata"."review_decision" RENAME COLUMN "review_request_id" TO "rvw_dmnd_id";
ALTER TABLE "metadata"."review_decision" RENAME COLUMN "reviewed_content_hash" TO "rvw_cn_hash_vl";
ALTER TABLE "metadata"."review_decision" RENAME COLUMN "decision" TO "dcsn_cd";
ALTER TABLE "metadata"."review_decision" RENAME COLUMN "reviewer_id" TO "rvwr_id";
ALTER TABLE "metadata"."review_decision" RENAME COLUMN "reason" TO "rsn";
ALTER TABLE "metadata"."review_decision" RENAME COLUMN "decided_at" TO "dcsn_dt";
COMMENT ON TABLE "metadata"."review_decision" IS '메타데이터 검토 결정. 검토 요청에 대한 승인·반려, 대상 해시와 결정자를 기록한다.';
COMMENT ON COLUMN "metadata"."review_decision"."id" IS '메타데이터 검토 결정의 아이디.';
COMMENT ON COLUMN "metadata"."review_decision"."rvw_dmnd_id" IS '메타데이터 검토 결정의 검토요청아이디. metadata.review_request 참조.';
COMMENT ON COLUMN "metadata"."review_decision"."rvw_cn_hash_vl" IS '메타데이터 검토 결정의 검토내용해시값. metadata.review_request 참조.';
COMMENT ON COLUMN "metadata"."review_decision"."dcsn_cd" IS '메타데이터 검토 결정의 결정코드. 허용 코드: APPROVE, REJECT.';
COMMENT ON COLUMN "metadata"."review_decision"."rvwr_id" IS '메타데이터 검토 결정의 검토자아이디.';
COMMENT ON COLUMN "metadata"."review_decision"."rsn" IS '메타데이터 검토 결정의 사유.';
COMMENT ON COLUMN "metadata"."review_decision"."dcsn_dt" IS '메타데이터 검토 결정의 결정일시.';

-- metadata.review_request
ALTER TABLE "metadata"."review_request" RENAME COLUMN "dataset_id" TO "dtset_id";
ALTER TABLE "metadata"."review_request" RENAME COLUMN "revision_id" TO "rvsn_id";
ALTER TABLE "metadata"."review_request" RENAME COLUMN "content_hash" TO "cn_hash_vl";
ALTER TABLE "metadata"."review_request" RENAME COLUMN "status" TO "stts_cd";
ALTER TABLE "metadata"."review_request" RENAME COLUMN "submitted_by" TO "sbmsn_user_id";
ALTER TABLE "metadata"."review_request" RENAME COLUMN "submitted_at" TO "sbmsn_dt";
ALTER TABLE "metadata"."review_request" RENAME COLUMN "lock_version" TO "lck_ver_no";
COMMENT ON TABLE "metadata"."review_request" IS '메타데이터 검토 요청. 데이터셋 개정본과 검토할 내용 해시·제출 상태를 기록한다.';
COMMENT ON COLUMN "metadata"."review_request"."id" IS '메타데이터 검토 요청의 아이디.';
COMMENT ON COLUMN "metadata"."review_request"."dtset_id" IS '메타데이터 검토 요청의 데이터셋아이디. catalog.dataset_revision 참조.';
COMMENT ON COLUMN "metadata"."review_request"."rvsn_id" IS '메타데이터 검토 요청의 개정아이디. catalog.dataset_revision 참조.';
COMMENT ON COLUMN "metadata"."review_request"."cn_hash_vl" IS '메타데이터 검토 요청의 내용해시값.';
COMMENT ON COLUMN "metadata"."review_request"."stts_cd" IS '메타데이터 검토 요청의 상태코드. 허용 코드: SUBMITTED, APPROVED, REJECTED, WITHDRAWN.';
COMMENT ON COLUMN "metadata"."review_request"."sbmsn_user_id" IS '메타데이터 검토 요청의 제출사용자아이디.';
COMMENT ON COLUMN "metadata"."review_request"."sbmsn_dt" IS '메타데이터 검토 요청의 제출일시.';
COMMENT ON COLUMN "metadata"."review_request"."lck_ver_no" IS '메타데이터 검토 요청의 잠금버전번호. 낙관적 잠금 비교용 버전으로 값 변경 의미를 보존한다.';

-- standard.definition_application
ALTER TABLE "standard"."definition_application" RENAME COLUMN "import_id" TO "dfn_inpt_id";
ALTER TABLE "standard"."definition_application" RENAME COLUMN "dataset_id" TO "dtset_id";
ALTER TABLE "standard"."definition_application" RENAME COLUMN "revision_id" TO "rvsn_id";
ALTER TABLE "standard"."definition_application" RENAME COLUMN "requested_by" TO "rqstr_id";
ALTER TABLE "standard"."definition_application" RENAME COLUMN "idempotency_digest" TO "idmpt_key_hash_vl";
ALTER TABLE "standard"."definition_application" RENAME COLUMN "request_hash" TO "dmnd_hash_vl";
ALTER TABLE "standard"."definition_application" RENAME COLUMN "selected_field_ids" TO "chc_data_artcl_id_cn";
ALTER TABLE "standard"."definition_application" RENAME COLUMN "result" TO "rslt_cn";
ALTER TABLE "standard"."definition_application" RENAME COLUMN "created_at" TO "crt_dt";
COMMENT ON TABLE "standard"."definition_application" IS '정의서 선택 필드 적용 이력. 입력 정의서에서 선택한 필드를 초안에 적용한 결과와 멱등 정보를 기록한다.';
COMMENT ON COLUMN "standard"."definition_application"."id" IS '정의서 선택 필드 적용 이력의 아이디.';
COMMENT ON COLUMN "standard"."definition_application"."dfn_inpt_id" IS '정의서 선택 필드 적용 이력의 정의입력아이디. standard.definition_import 참조.';
COMMENT ON COLUMN "standard"."definition_application"."dtset_id" IS '정의서 선택 필드 적용 이력의 데이터셋아이디. catalog.dataset 참조; catalog.dataset_revision 참조.';
COMMENT ON COLUMN "standard"."definition_application"."rvsn_id" IS '정의서 선택 필드 적용 이력의 개정아이디. catalog.dataset_revision 참조; catalog.dataset_revision 참조.';
COMMENT ON COLUMN "standard"."definition_application"."rqstr_id" IS '정의서 선택 필드 적용 이력의 요청자아이디.';
COMMENT ON COLUMN "standard"."definition_application"."idmpt_key_hash_vl" IS '정의서 선택 필드 적용 이력의 멱등키해시값.';
COMMENT ON COLUMN "standard"."definition_application"."dmnd_hash_vl" IS '정의서 선택 필드 적용 이력의 요청해시값.';
COMMENT ON COLUMN "standard"."definition_application"."chc_data_artcl_id_cn" IS '정의서 선택 필드 적용 이력의 선택자료항목아이디내용.';
COMMENT ON COLUMN "standard"."definition_application"."rslt_cn" IS '정의서 선택 필드 적용 이력의 결과내용.';
COMMENT ON COLUMN "standard"."definition_application"."crt_dt" IS '정의서 선택 필드 적용 이력의 생성일시.';

-- standard.definition_field
ALTER TABLE "standard"."definition_field" RENAME COLUMN "import_id" TO "dfn_inpt_id";
ALTER TABLE "standard"."definition_field" RENAME COLUMN "ordinal" TO "sort_seq";
ALTER TABLE "standard"."definition_field" RENAME COLUMN "field_definition" TO "data_artcl_dfn_cn";
ALTER TABLE "standard"."definition_field" RENAME COLUMN "source_pointer" TO "sou_ptr_vl";
COMMENT ON TABLE "standard"."definition_field" IS '정의서 추출 필드. 입력 원본에서 추출한 필드 정의·순서와 JSON Pointer 근거를 보관한다.';
COMMENT ON COLUMN "standard"."definition_field"."id" IS '정의서 추출 필드의 아이디.';
COMMENT ON COLUMN "standard"."definition_field"."dfn_inpt_id" IS '정의서 추출 필드의 정의입력아이디. standard.definition_import 참조.';
COMMENT ON COLUMN "standard"."definition_field"."sort_seq" IS '정의서 추출 필드의 정렬순서.';
COMMENT ON COLUMN "standard"."definition_field"."data_artcl_dfn_cn" IS '정의서 추출 필드의 자료항목정의내용.';
COMMENT ON COLUMN "standard"."definition_field"."sou_ptr_vl" IS '정의서 추출 필드의 원천포인터값.';

-- standard.definition_import
ALTER TABLE "standard"."definition_import" RENAME COLUMN "organization_id" TO "inst_id";
ALTER TABLE "standard"."definition_import" RENAME COLUMN "dataset_id" TO "dtset_id";
ALTER TABLE "standard"."definition_import" RENAME COLUMN "requested_by" TO "rqstr_id";
ALTER TABLE "standard"."definition_import" RENAME COLUMN "source_hash" TO "sou_hash_vl";
ALTER TABLE "standard"."definition_import" RENAME COLUMN "source_size" TO "sou_sz";
ALTER TABLE "standard"."definition_import" RENAME COLUMN "parser_version" TO "prsr_ver_nm";
ALTER TABLE "standard"."definition_import" RENAME COLUMN "status" TO "stts_cd";
ALTER TABLE "standard"."definition_import" RENAME COLUMN "field_count" TO "data_artcl_cnt";
ALTER TABLE "standard"."definition_import" RENAME COLUMN "warnings" TO "wrng_cn";
ALTER TABLE "standard"."definition_import" RENAME COLUMN "created_at" TO "crt_dt";
COMMENT ON TABLE "standard"."definition_import" IS '정의서 입력 이력. 기관별 정의서 입력 검증 결과·원본 해시·크기·해석기 버전을 기록한다.';
COMMENT ON COLUMN "standard"."definition_import"."id" IS '정의서 입력 이력의 아이디.';
COMMENT ON COLUMN "standard"."definition_import"."inst_id" IS '정의서 입력 이력의 기관아이디. catalog.dataset 참조; identity.organization 참조.';
COMMENT ON COLUMN "standard"."definition_import"."dtset_id" IS '정의서 입력 이력의 데이터셋아이디. catalog.dataset 참조; catalog.dataset 참조.';
COMMENT ON COLUMN "standard"."definition_import"."rqstr_id" IS '정의서 입력 이력의 요청자아이디.';
COMMENT ON COLUMN "standard"."definition_import"."sou_hash_vl" IS '정의서 입력 이력의 원천해시값.';
COMMENT ON COLUMN "standard"."definition_import"."sou_sz" IS '정의서 입력 이력의 원천크기. 입력 원본 바이트 수.';
COMMENT ON COLUMN "standard"."definition_import"."prsr_ver_nm" IS '정의서 입력 이력의 해석기버전명.';
COMMENT ON COLUMN "standard"."definition_import"."stts_cd" IS '정의서 입력 이력의 상태코드. 허용 코드: VALIDATED.';
COMMENT ON COLUMN "standard"."definition_import"."data_artcl_cnt" IS '정의서 입력 이력의 자료항목수.';
COMMENT ON COLUMN "standard"."definition_import"."wrng_cn" IS '정의서 입력 이력의 경고내용.';
COMMENT ON COLUMN "standard"."definition_import"."crt_dt" IS '정의서 입력 이력의 생성일시.';

-- standard.dictionary
ALTER TABLE "standard"."dictionary" RENAME COLUMN "scope" TO "rng_cd";
ALTER TABLE "standard"."dictionary" RENAME COLUMN "organization_id" TO "inst_id";
ALTER TABLE "standard"."dictionary" RENAME COLUMN "code" TO "cd";
ALTER TABLE "standard"."dictionary" RENAME COLUMN "name" TO "nm";
ALTER TABLE "standard"."dictionary" RENAME COLUMN "source_uri" TO "sou_rfrnc_addr";
ALTER TABLE "standard"."dictionary" RENAME COLUMN "version_label" TO "ver_nm";
ALTER TABLE "standard"."dictionary" RENAME COLUMN "source_checksum" TO "sou_hash_vl";
ALTER TABLE "standard"."dictionary" RENAME COLUMN "created_at" TO "crt_dt";
COMMENT ON TABLE "standard"."dictionary" IS '표준사전. 공공·기관 범위의 사전 원본·버전·출처를 관리한다.';
COMMENT ON COLUMN "standard"."dictionary"."id" IS '표준사전의 아이디.';
COMMENT ON COLUMN "standard"."dictionary"."rng_cd" IS '표준사전의 범위코드. 허용 코드: PUBLIC, ORGANIZATION.';
COMMENT ON COLUMN "standard"."dictionary"."inst_id" IS '표준사전의 기관아이디. identity.organization 참조.';
COMMENT ON COLUMN "standard"."dictionary"."cd" IS '표준사전의 코드.';
COMMENT ON COLUMN "standard"."dictionary"."nm" IS '표준사전의 명.';
COMMENT ON COLUMN "standard"."dictionary"."sou_rfrnc_addr" IS '표준사전의 원천참조주소.';
COMMENT ON COLUMN "standard"."dictionary"."ver_nm" IS '표준사전의 버전명.';
COMMENT ON COLUMN "standard"."dictionary"."sou_hash_vl" IS '표준사전의 원천해시값.';
COMMENT ON COLUMN "standard"."dictionary"."crt_dt" IS '표준사전의 생성일시.';

-- standard.dictionary_entry
ALTER TABLE "standard"."dictionary_entry" RENAME COLUMN "dictionary_id" TO "stddict_id";
ALTER TABLE "standard"."dictionary_entry" RENAME COLUMN "kind" TO "type_cd";
ALTER TABLE "standard"."dictionary_entry" RENAME COLUMN "code" TO "cd";
ALTER TABLE "standard"."dictionary_entry" RENAME COLUMN "name" TO "nm";
ALTER TABLE "standard"."dictionary_entry" RENAME COLUMN "definition" TO "dfn_cn";
ALTER TABLE "standard"."dictionary_entry" RENAME COLUMN "data_type" TO "data_type_cd";
ALTER TABLE "standard"."dictionary_entry" RENAME COLUMN "state" TO "stts_cd";
ALTER TABLE "standard"."dictionary_entry" RENAME COLUMN "lock_version" TO "lck_ver_no";
ALTER TABLE "standard"."dictionary_entry" RENAME COLUMN "created_by" TO "creatr_id";
ALTER TABLE "standard"."dictionary_entry" RENAME COLUMN "submitted_by" TO "sbmsn_user_id";
ALTER TABLE "standard"."dictionary_entry" RENAME COLUMN "decided_by" TO "dcsn_user_id";
ALTER TABLE "standard"."dictionary_entry" RENAME COLUMN "decision_reason" TO "dcsn_rsn";
ALTER TABLE "standard"."dictionary_entry" RENAME COLUMN "created_at" TO "crt_dt";
ALTER TABLE "standard"."dictionary_entry" RENAME COLUMN "updated_at" TO "mdfcn_dt";
ALTER TABLE "standard"."dictionary_entry" RENAME COLUMN "updated_by" TO "mdfr_id";
COMMENT ON TABLE "standard"."dictionary_entry" IS '표준사전 항목. 단어·용어·도메인 항목의 초안·검토·승인 상태를 관리한다.';
COMMENT ON COLUMN "standard"."dictionary_entry"."id" IS '표준사전 항목의 아이디.';
COMMENT ON COLUMN "standard"."dictionary_entry"."stddict_id" IS '표준사전 항목의 표준사전아이디. standard.dictionary 참조.';
COMMENT ON COLUMN "standard"."dictionary_entry"."type_cd" IS '표준사전 항목의 유형코드. 허용 코드: WORD, TERM, DOMAIN.';
COMMENT ON COLUMN "standard"."dictionary_entry"."cd" IS '표준사전 항목의 코드.';
COMMENT ON COLUMN "standard"."dictionary_entry"."nm" IS '표준사전 항목의 명.';
COMMENT ON COLUMN "standard"."dictionary_entry"."dfn_cn" IS '표준사전 항목의 정의내용.';
COMMENT ON COLUMN "standard"."dictionary_entry"."data_type_cd" IS '표준사전 항목의 자료유형코드. 허용 코드: STRING, INTEGER, NUMBER, BOOLEAN, DATE, DATETIME, OBJECT, ARRAY.';
COMMENT ON COLUMN "standard"."dictionary_entry"."stts_cd" IS '표준사전 항목의 상태코드. 허용 코드: DRAFT, IN_REVIEW, APPROVED, REJECTED.';
COMMENT ON COLUMN "standard"."dictionary_entry"."lck_ver_no" IS '표준사전 항목의 잠금버전번호. 낙관적 잠금 비교용 버전으로 값 변경 의미를 보존한다.';
COMMENT ON COLUMN "standard"."dictionary_entry"."creatr_id" IS '표준사전 항목의 생성자ID.';
COMMENT ON COLUMN "standard"."dictionary_entry"."sbmsn_user_id" IS '표준사전 항목의 제출사용자아이디.';
COMMENT ON COLUMN "standard"."dictionary_entry"."dcsn_user_id" IS '표준사전 항목의 결정사용자아이디.';
COMMENT ON COLUMN "standard"."dictionary_entry"."dcsn_rsn" IS '표준사전 항목의 결정사유.';
COMMENT ON COLUMN "standard"."dictionary_entry"."crt_dt" IS '표준사전 항목의 생성일시.';
COMMENT ON COLUMN "standard"."dictionary_entry"."mdfcn_dt" IS '표준사전 항목의 수정일시.';
COMMENT ON COLUMN "standard"."dictionary_entry"."mdfr_id" IS '표준사전 항목의 수정자ID.';

-- standard.dictionary_export
ALTER TABLE "standard"."dictionary_export" RENAME COLUMN "dictionary_id" TO "stddict_id";
ALTER TABLE "standard"."dictionary_export" RENAME COLUMN "requested_by" TO "rqstr_id";
ALTER TABLE "standard"."dictionary_export" RENAME COLUMN "kind" TO "type_cd";
ALTER TABLE "standard"."dictionary_export" RENAME COLUMN "query" TO "srch_cn";
ALTER TABLE "standard"."dictionary_export" RENAME COLUMN "state_filter" TO "stts_cnd_cd";
ALTER TABLE "standard"."dictionary_export" RENAME COLUMN "key_digest" TO "key_hash_vl";
ALTER TABLE "standard"."dictionary_export" RENAME COLUMN "request_hash" TO "dmnd_hash_vl";
ALTER TABLE "standard"."dictionary_export" RENAME COLUMN "entry_count" TO "artcl_cnt";
ALTER TABLE "standard"."dictionary_export" RENAME COLUMN "file_content" TO "file_cn";
ALTER TABLE "standard"."dictionary_export" RENAME COLUMN "content_hash" TO "cn_hash_vl";
ALTER TABLE "standard"."dictionary_export" RENAME COLUMN "created_at" TO "crt_dt";
ALTER TABLE "standard"."dictionary_export" RENAME COLUMN "expires_at" TO "expry_dt";
COMMENT ON TABLE "standard"."dictionary_export" IS '표준사전 추출 파일. 권한과 필터를 적용해 생성한 사전 XLSX 파일과 멱등·만료 정보를 보관한다.';
COMMENT ON COLUMN "standard"."dictionary_export"."id" IS '표준사전 추출 파일의 아이디.';
COMMENT ON COLUMN "standard"."dictionary_export"."stddict_id" IS '표준사전 추출 파일의 표준사전아이디. standard.dictionary 참조.';
COMMENT ON COLUMN "standard"."dictionary_export"."rqstr_id" IS '표준사전 추출 파일의 요청자아이디.';
COMMENT ON COLUMN "standard"."dictionary_export"."type_cd" IS '표준사전 추출 파일의 유형코드. 허용 코드: WORD, TERM, DOMAIN.';
COMMENT ON COLUMN "standard"."dictionary_export"."srch_cn" IS '표준사전 추출 파일의 검색내용.';
COMMENT ON COLUMN "standard"."dictionary_export"."stts_cnd_cd" IS '표준사전 추출 파일의 상태조건코드. 허용 코드: DRAFT, IN_REVIEW, APPROVED, REJECTED.';
COMMENT ON COLUMN "standard"."dictionary_export"."key_hash_vl" IS '표준사전 추출 파일의 키해시값.';
COMMENT ON COLUMN "standard"."dictionary_export"."dmnd_hash_vl" IS '표준사전 추출 파일의 요청해시값.';
COMMENT ON COLUMN "standard"."dictionary_export"."artcl_cnt" IS '표준사전 추출 파일의 항목수.';
COMMENT ON COLUMN "standard"."dictionary_export"."file_cn" IS '표준사전 추출 파일의 파일내용.';
COMMENT ON COLUMN "standard"."dictionary_export"."cn_hash_vl" IS '표준사전 추출 파일의 내용해시값.';
COMMENT ON COLUMN "standard"."dictionary_export"."crt_dt" IS '표준사전 추출 파일의 생성일시.';
COMMENT ON COLUMN "standard"."dictionary_export"."expry_dt" IS '표준사전 추출 파일의 만료일시.';

-- standard.domain_definition
ALTER TABLE "standard"."domain_definition" RENAME COLUMN "entry_id" TO "artcl_id";
ALTER TABLE "standard"."domain_definition" RENAME COLUMN "max_length" TO "max_len";
ALTER TABLE "standard"."domain_definition" RENAME COLUMN "numeric_precision" TO "prcsn";
ALTER TABLE "standard"."domain_definition" RENAME COLUMN "numeric_scale" TO "dcpt_len";
ALTER TABLE "standard"."domain_definition" RENAME COLUMN "format" TO "frm_nm";
ALTER TABLE "standard"."domain_definition" RENAME COLUMN "unit" TO "unit_nm";
ALTER TABLE "standard"."domain_definition" RENAME COLUMN "allowed_values" TO "prm_vl_cn";
COMMENT ON TABLE "standard"."domain_definition" IS '표준도메인 정의. 표준사전 도메인 항목의 길이·정밀도·형식·단위·허용값을 정의한다.';
COMMENT ON COLUMN "standard"."domain_definition"."artcl_id" IS '표준도메인 정의의 항목아이디. standard.dictionary_entry 참조.';
COMMENT ON COLUMN "standard"."domain_definition"."max_len" IS '표준도메인 정의의 최대길이.';
COMMENT ON COLUMN "standard"."domain_definition"."prcsn" IS '표준도메인 정의의 정밀도.';
COMMENT ON COLUMN "standard"."domain_definition"."dcpt_len" IS '표준도메인 정의의 소수점길이.';
COMMENT ON COLUMN "standard"."domain_definition"."frm_nm" IS '표준도메인 정의의 형식명.';
COMMENT ON COLUMN "standard"."domain_definition"."unit_nm" IS '표준도메인 정의의 단위명.';
COMMENT ON COLUMN "standard"."domain_definition"."prm_vl_cn" IS '표준도메인 정의의 허용값내용.';

-- standard.term_definition
ALTER TABLE "standard"."term_definition" RENAME COLUMN "entry_id" TO "artcl_id";
ALTER TABLE "standard"."term_definition" RENAME COLUMN "physical_name" TO "trm_eng_abbr_nm";
ALTER TABLE "standard"."term_definition" RENAME COLUMN "domain_entry_id" TO "stddmn_artcl_id";
COMMENT ON TABLE "standard"."term_definition" IS '표준용어 정의. 표준용어의 물리 영문명과 승인된 도메인 관계를 정의한다.';
COMMENT ON COLUMN "standard"."term_definition"."artcl_id" IS '표준용어 정의의 항목아이디. standard.dictionary_entry 참조.';
COMMENT ON COLUMN "standard"."term_definition"."trm_eng_abbr_nm" IS '표준용어 정의의 용어영문약어명.';
COMMENT ON COLUMN "standard"."term_definition"."stddmn_artcl_id" IS '표준용어 정의의 표준도메인항목아이디. standard.dictionary_entry 참조.';

-- standard.term_word
ALTER TABLE "standard"."term_word" RENAME COLUMN "term_entry_id" TO "trm_artcl_id";
ALTER TABLE "standard"."term_word" RENAME COLUMN "word_entry_id" TO "word_artcl_id";
ALTER TABLE "standard"."term_word" RENAME COLUMN "ordinal" TO "sort_seq";
COMMENT ON TABLE "standard"."term_word" IS '표준용어 구성 단어. 용어를 구성하는 승인 단어와 결합 순서를 기록한다.';
COMMENT ON COLUMN "standard"."term_word"."trm_artcl_id" IS '표준용어 구성 단어의 용어항목아이디. standard.term_definition 참조.';
COMMENT ON COLUMN "standard"."term_word"."word_artcl_id" IS '표준용어 구성 단어의 단어항목아이디. standard.dictionary_entry 참조.';
COMMENT ON COLUMN "standard"."term_word"."sort_seq" IS '표준용어 구성 단어의 정렬순서.';

-- standard.word_definition
ALTER TABLE "standard"."word_definition" RENAME COLUMN "entry_id" TO "artcl_id";
ALTER TABLE "standard"."word_definition" RENAME COLUMN "english_name" TO "eng_nm";
ALTER TABLE "standard"."word_definition" RENAME COLUMN "abbreviation" TO "abbr_nm";
ALTER TABLE "standard"."word_definition" RENAME COLUMN "synonyms" TO "synm_cn";
COMMENT ON TABLE "standard"."word_definition" IS '표준단어 정의. 표준단어의 영문 전체명·약어와 동의어를 정의한다.';
COMMENT ON COLUMN "standard"."word_definition"."artcl_id" IS '표준단어 정의의 항목아이디. standard.dictionary_entry 참조.';
COMMENT ON COLUMN "standard"."word_definition"."eng_nm" IS '표준단어 정의의 영문명.';
COMMENT ON COLUMN "standard"."word_definition"."abbr_nm" IS '표준단어 정의의 약어명.';
COMMENT ON COLUMN "standard"."word_definition"."synm_cn" IS '표준단어 정의의 동의어내용.';

-- PL/pgSQL function bodies keep their original guards with renamed physical attributes.
-- ALTER TABLE tracks constraint/index/trigger dependencies; stored function text needs explicit replacement.

CREATE OR REPLACE FUNCTION analytics.validate_analysis_scope()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    IF NEW.dtset_rvsn_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM catalog.dataset_revision r
        JOIN catalog.dataset d ON d.id=r.dtset_id WHERE r.id=NEW.dtset_rvsn_id AND d.psn_inst_id=NEW.inst_id) THEN
        RAISE EXCEPTION 'Analysis revision organization mismatch' USING ERRCODE='23514';
    END IF;
    IF NEW.dfn_inpt_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM standard.definition_import i
        WHERE i.id=NEW.dfn_inpt_id AND i.inst_id=NEW.inst_id) THEN
        RAISE EXCEPTION 'Analysis import organization mismatch' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION apidocs.require_matching_schema()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM delivery.data_service_version s WHERE s.id = NEW.srvc_ver_id
        AND s.schm_hash_vl = NEW.schm_hash_vl AND s.stts_cd <> 'REVOKED') THEN
        RAISE EXCEPTION 'API document must match the service schema' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION catalog.protect_approved_revision()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    IF OLD.stts_cd IN ('APPROVED','REJECTED') THEN
        RAISE EXCEPTION 'Decided revision is immutable' USING ERRCODE='23514';
    END IF;
    IF OLD.stts_cd='IN_REVIEW' AND (TG_OP='DELETE' OR
       (to_jsonb(NEW)-'stts_cd') IS DISTINCT FROM (to_jsonb(OLD)-'stts_cd')) THEN
        RAISE EXCEPTION 'Submitted revision content is immutable' USING ERRCODE='23514';
    END IF;
    IF OLD.stts_cd='IN_REVIEW' AND NEW.stts_cd NOT IN ('APPROVED','REJECTED') THEN
        RAISE EXCEPTION 'Submitted revision requires a review outcome' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION catalog.protect_discovery_lookup()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Discovery lookups are deactivated, not deleted' USING ERRCODE='23514';
    END IF;
    IF (NEW.id,NEW.type_cd,NEW.cd,NEW.crt_dt) IS DISTINCT FROM (OLD.id,OLD.type_cd,OLD.cd,OLD.crt_dt) THEN
        RAISE EXCEPTION 'Discovery lookup identity is immutable' USING ERRCODE='23514';
    END IF;
    IF NOT NEW.actvtn_yn AND EXISTS (SELECT 1 FROM catalog.revision_discovery WHERE clsf_id=OLD.id) THEN
        RAISE EXCEPTION 'Referenced lookup cannot be deactivated' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION catalog.protect_discovery_reference()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE target uuid; revision_state varchar(20);
BEGIN
    target := CASE WHEN TG_OP='DELETE' THEN OLD.rvsn_id ELSE NEW.rvsn_id END;
    SELECT stts_cd INTO revision_state FROM catalog.dataset_revision WHERE id=target FOR UPDATE;
    IF revision_state IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'Discovery references require a mutable draft' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' AND NEW.rvsn_id IS DISTINCT FROM OLD.rvsn_id THEN
        RAISE EXCEPTION 'Reference revision identity is immutable' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION catalog.protect_referenced_topic()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    IF (NOT NEW.actvtn_yn OR NEW.up_id IS DISTINCT FROM OLD.up_id) AND
      EXISTS (SELECT 1 FROM catalog.revision_topic WHERE tpc_id=OLD.id) THEN
        RAISE EXCEPTION 'Referenced topic cannot be deactivated or moved' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION catalog.require_approved_publication()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    IF NEW.stts_cd = 'PUBLISHED' AND NOT EXISTS (
        SELECT 1 FROM catalog.dataset_revision r
        WHERE r.id = NEW.rvsn_id AND r.dtset_id = NEW.dtset_id AND r.stts_cd = 'APPROVED'
    ) THEN
        RAISE EXCEPTION 'Publication requires an approved revision of the same dataset' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.dtset_id, NEW.rvsn_id, NEW.pstg_no, NEW.pstg_dt)
        IS DISTINCT FROM (OLD.dtset_id, OLD.rvsn_id, OLD.pstg_no, OLD.pstg_dt) THEN
        RAISE EXCEPTION 'Publication identity and snapshot are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION catalog.validate_discovery_reference()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    PERFORM 1 FROM catalog.discovery_lookup WHERE id=NEW.clsf_id AND type_cd=NEW.type_cd AND actvtn_yn FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Active matching discovery lookup required' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION catalog.validate_topic_reference()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    PERFORM 1 FROM classification.category t JOIN classification.category parent ON parent.id=t.up_id
      JOIN catalog.dataset_revision r ON r.id=NEW.rvsn_id
      JOIN classification.category selected ON selected.id=r.main_clsf_id
      WHERE t.id=NEW.tpc_id AND t.actvtn_yn AND parent.actvtn_yn
        AND t.up_id=coalesce(selected.up_id,selected.id) FOR SHARE OF t,parent,selected;
    IF NOT FOUND THEN RAISE EXCEPTION 'Topic requires its active selected parent' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION classification.protect_category_use()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    IF TG_OP='DELETE' OR (OLD.actvtn_yn AND NOT NEW.actvtn_yn) THEN
        IF EXISTS(SELECT 1 FROM catalog.dataset_revision WHERE main_clsf_id=OLD.id)
           OR EXISTS(SELECT 1 FROM classification.category WHERE up_id=OLD.id) THEN
            RAISE EXCEPTION 'Category is referenced' USING ERRCODE='23514';
        END IF;
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION classification.require_active_parent()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    IF TG_OP='UPDATE' AND (NEW.cd,NEW.up_id) IS DISTINCT FROM (OLD.cd,OLD.up_id) THEN
        RAISE EXCEPTION 'Category identity is immutable' USING ERRCODE='23514';
    END IF;
    IF NEW.up_id IS NOT NULL THEN
        PERFORM 1 FROM classification.category WHERE id=NEW.up_id AND up_id IS NULL AND actvtn_yn FOR SHARE;
        IF NOT FOUND THEN RAISE EXCEPTION 'Active root category required' USING ERRCODE='23514'; END IF;
    END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION classification.require_active_reference()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    IF NEW.main_clsf_id IS NOT NULL THEN
        PERFORM 1 FROM classification.category WHERE id=NEW.main_clsf_id AND actvtn_yn FOR SHARE;
        IF NOT FOUND THEN RAISE EXCEPTION 'Active category required' USING ERRCODE='23514'; END IF;
    END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION delivery.protect_verified_contract()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    IF OLD.stts_cd <> 'DRAFT' THEN
        IF TG_OP = 'DELETE' OR (to_jsonb(NEW) - 'stts_cd') IS DISTINCT FROM (to_jsonb(OLD) - 'stts_cd')
            OR NEW.stts_cd <> 'REVOKED' THEN
            RAISE EXCEPTION 'Verified contracts are immutable; revocation is allowed' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION delivery.require_real_publication_contract()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM delivery.data_service_version s
        JOIN catalog.publication p ON p.id = NEW.pstg_id AND p.dtset_id = s.dtset_id
        WHERE s.id = NEW.srvc_ver_id AND s.dtset_id = NEW.dtset_id
          AND s.stts_cd = 'VERIFIED' AND s.vrfc_lvl_cd = 'REAL_CONTRACT'
          AND ((s.pvsn_type_cd = 'LINK' AND p.acs_stts_cd = 'LINK_READY')
            OR (s.pvsn_type_cd IN ('PROXY', 'MATERIALIZED') AND p.acs_stts_cd = 'SERVICE_READY'))
          AND p.stts_cd = 'PUBLISHED') THEN
        RAISE EXCEPTION 'Publication requires a verified real delivery contract and matching access state' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION metadata.protect_review_request()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    IF TG_OP='DELETE' OR (to_jsonb(NEW)-'stts_cd'-'lck_ver_no') IS DISTINCT FROM (to_jsonb(OLD)-'stts_cd'-'lck_ver_no') THEN
        RAISE EXCEPTION 'Review submission is immutable' USING ERRCODE='23514';
    END IF;
    IF OLD.stts_cd <> 'SUBMITTED' OR NEW.stts_cd NOT IN ('APPROVED','REJECTED','WITHDRAWN') OR NEW.lck_ver_no <> OLD.lck_ver_no+1 THEN
        RAISE EXCEPTION 'Invalid review state transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$function$;

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
    IF EXISTS (SELECT 1 FROM metadata.review_request q JOIN catalog.dataset_revision r ON r.id=q.rvsn_id
        WHERE q.id=NEW.rvw_dmnd_id AND (q.sbmsn_user_id=NEW.rvwr_id OR r.mdfr_id=NEW.rvwr_id OR r.creatr_id=NEW.rvwr_id)) THEN
        RAISE EXCEPTION 'Self review is prohibited' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION metadata.validate_publication_approval()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    IF EXISTS (SELECT 1 FROM catalog.dataset_revision WHERE id=NEW.rvsn_id AND (cn_hash_vl IS NOT NULL OR creatr_id IS NOT NULL))
        AND NOT EXISTS (SELECT 1 FROM metadata.review_decision d JOIN metadata.review_request q ON q.id=d.rvw_dmnd_id
            JOIN catalog.dataset_revision r ON r.id=q.rvsn_id
            WHERE d.id=NEW.rvw_dcsn_id AND q.dtset_id=NEW.dtset_id AND q.rvsn_id=NEW.rvsn_id
              AND d.dcsn_cd='APPROVE' AND q.stts_cd='APPROVED' AND r.cn_hash_vl=d.rvw_cn_hash_vl) THEN
        RAISE EXCEPTION 'Publication requires matching approval' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' AND (NEW.rvw_dcsn_id,NEW.pstg_user_id) IS DISTINCT FROM (OLD.rvw_dcsn_id,OLD.pstg_user_id) THEN
        RAISE EXCEPTION 'Publication approval is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION standard.protect_entry()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    IF OLD.stts_cd IN ('APPROVED','REJECTED') THEN
        RAISE EXCEPTION 'Decided dictionary entries are immutable' USING ERRCODE='23514';
    END IF;
    IF EXISTS (SELECT 1 FROM standard.dictionary WHERE id=OLD.stddict_id AND rng_cd='PUBLIC') THEN
        RAISE EXCEPTION 'Public dictionary source is immutable' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN
        IF OLD.stts_cd <> 'DRAFT' THEN RAISE EXCEPTION 'Submitted dictionary entries cannot be deleted' USING ERRCODE='23514'; END IF;
        RETURN OLD;
    END IF;
    IF (NEW.stddict_id,NEW.type_cd,NEW.cd,NEW.creatr_id) IS DISTINCT FROM (OLD.stddict_id,OLD.type_cd,OLD.cd,OLD.creatr_id)
       OR (OLD.stts_cd <> 'DRAFT' AND (NEW.nm,NEW.dfn_cn,NEW.data_type_cd,NEW.mdfr_id,NEW.sbmsn_user_id)
           IS DISTINCT FROM (OLD.nm,OLD.dfn_cn,OLD.data_type_cd,OLD.mdfr_id,OLD.sbmsn_user_id)) THEN
        RAISE EXCEPTION 'Dictionary identity and review content are immutable' USING ERRCODE='23514';
    END IF;
    IF OLD.stts_cd='DRAFT' AND NEW.stts_cd NOT IN ('DRAFT','IN_REVIEW')
       OR OLD.stts_cd='IN_REVIEW' AND NEW.stts_cd NOT IN ('APPROVED','REJECTED') THEN
        RAISE EXCEPTION 'Invalid dictionary review transition' USING ERRCODE='23514';
    END IF;
    IF NEW.stts_cd='IN_REVIEW' AND NEW.sbmsn_user_id IS NULL THEN
        RAISE EXCEPTION 'Dictionary submission actor required' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION standard.protect_relationship()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
    record_json jsonb; old_json jsonb; entry_key uuid; expected_kind text; entry_row record;
BEGIN
    record_json=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    IF TG_OP='UPDATE' THEN old_json=to_jsonb(OLD); END IF;
    entry_key=coalesce(record_json->>'artcl_id',record_json->>'trm_artcl_id')::uuid;
    IF TG_OP='UPDATE' AND coalesce(record_json->>'artcl_id',record_json->>'trm_artcl_id')
        IS DISTINCT FROM coalesce(old_json->>'artcl_id',old_json->>'trm_artcl_id') THEN
        RAISE EXCEPTION 'Relationship identity is immutable' USING ERRCODE='23514';
    END IF;
    expected_kind=CASE TG_TABLE_NAME WHEN 'word_definition' THEN 'WORD' WHEN 'domain_definition' THEN 'DOMAIN' ELSE 'TERM' END;
    SELECT e.type_cd,e.stts_cd,d.rng_cd INTO entry_row FROM standard.dictionary_entry e JOIN standard.dictionary d ON d.id=e.stddict_id
        WHERE e.id=entry_key FOR UPDATE OF e;
    IF NOT FOUND OR entry_row.type_cd<>expected_kind OR entry_row.stts_cd<>'DRAFT' OR entry_row.rng_cd<>'ORGANIZATION' THEN
        RAISE EXCEPTION 'Only matching organization draft relationships are editable' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION standard.validate_definition_application()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM standard.definition_import i JOIN catalog.dataset d ON d.psn_inst_id=i.inst_id
        WHERE i.id=NEW.dfn_inpt_id AND d.id=NEW.dtset_id AND (i.dtset_id IS NULL OR i.dtset_id=d.id)) THEN
        RAISE EXCEPTION 'Definition application organization or dataset mismatch' USING ERRCODE='23514';
    END IF;
    IF jsonb_array_length(NEW.chc_data_artcl_id_cn) NOT BETWEEN 1 AND 500
       OR (SELECT count(DISTINCT selected.value) FROM jsonb_array_elements_text(NEW.chc_data_artcl_id_cn) selected(value)) <> jsonb_array_length(NEW.chc_data_artcl_id_cn)
       OR EXISTS(SELECT 1 FROM jsonb_array_elements_text(NEW.chc_data_artcl_id_cn) selected(value)
           WHERE NOT EXISTS(SELECT 1 FROM standard.definition_field f WHERE f.dfn_inpt_id=NEW.dfn_inpt_id AND f.id::text=selected.value)) THEN
        RAISE EXCEPTION 'Definition application selection does not belong to source import' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION standard.validate_term_review()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE domain_row record; owner_org uuid;
BEGIN
    IF NEW.type_cd='TERM' AND NEW.stts_cd IN ('IN_REVIEW','APPROVED') AND (TG_OP='INSERT' OR NEW.stts_cd IS DISTINCT FROM OLD.stts_cd) THEN
        SELECT inst_id INTO owner_org FROM standard.dictionary WHERE id=NEW.stddict_id;
        SELECT e.type_cd,e.stts_cd,e.data_type_cd,d.rng_cd,d.inst_id INTO domain_row FROM standard.term_definition t
            JOIN standard.dictionary_entry e ON e.id=t.stddmn_artcl_id JOIN standard.dictionary d ON d.id=e.stddict_id WHERE t.artcl_id=NEW.id;
        IF NOT FOUND OR domain_row.type_cd<>'DOMAIN' OR domain_row.stts_cd<>'APPROVED' OR domain_row.data_type_cd<>NEW.data_type_cd
            OR (domain_row.rng_cd<>'PUBLIC' AND domain_row.inst_id IS DISTINCT FROM owner_org) THEN
            RAISE EXCEPTION 'Term requires an approved compatible domain' USING ERRCODE='23514';
        END IF;
        IF NOT EXISTS(SELECT 1 FROM standard.term_word WHERE trm_artcl_id=NEW.id)
            OR EXISTS(SELECT 1 FROM standard.term_word tw JOIN standard.dictionary_entry e ON e.id=tw.word_artcl_id
                JOIN standard.dictionary d ON d.id=e.stddict_id WHERE tw.trm_artcl_id=NEW.id
                AND (e.type_cd<>'WORD' OR e.stts_cd<>'APPROVED' OR (d.rng_cd<>'PUBLIC' AND d.inst_id IS DISTINCT FROM owner_org))) THEN
            RAISE EXCEPTION 'Term requires approved words in permitted scope' USING ERRCODE='23514';
        END IF;
        IF (SELECT max(sort_seq)+1<>count(*) FROM standard.term_word WHERE trm_artcl_id=NEW.id) THEN
            RAISE EXCEPTION 'Term word order must be contiguous' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$function$;

