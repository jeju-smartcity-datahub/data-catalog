ALTER TABLE integration.source_metadata
    ADD COLUMN aplcn_mtdt_cn jsonb NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN integration.source_metadata.aplcn_mtdt_cn IS '마지막 적용 허브 메타데이터. 이후 수집 및 수동 편집의 비교 기준';

ALTER TABLE integration.source_metadata
    ADD CONSTRAINT source_metadata_applied_metadata_object CHECK (jsonb_typeof(aplcn_mtdt_cn) = 'object');
