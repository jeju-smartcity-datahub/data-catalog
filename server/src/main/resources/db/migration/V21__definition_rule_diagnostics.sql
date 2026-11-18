-- Preserve the actual assessment version and safe unextracted-item reasons.
-- Existing immutable records remain unassessed; no historical confidence is invented.
ALTER TABLE analytics.analysis_run ADD COLUMN dfn_dgns_cn jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE analytics.analysis_run ADD CONSTRAINT analysis_definition_diagnostics_object
    CHECK (jsonb_typeof(dfn_dgns_cn)='object');
COMMENT ON COLUMN analytics.analysis_run.dfn_dgns_cn IS '정의서 추출 규칙 점수와 항목별 미생성 사유. 과거 기록은 빈 객체이며 원문 값은 저장하지 않는다.';
-- The existing FAILED contract (zero generated fields, no applicable import, empty
-- field evidence) and immutable-row trigger remain in force.
