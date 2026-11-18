-- A rejected, authorized bounded definition parse is a failed analysis, never a validated import.
ALTER TABLE analytics.analysis_run DROP CONSTRAINT analysis_run_status_check;
ALTER TABLE analytics.analysis_run ADD COLUMN error_code varchar(80);
ALTER TABLE analytics.analysis_run ADD CONSTRAINT analysis_run_status_check CHECK(status IN ('SUCCEEDED','FAILED'));
ALTER TABLE analytics.analysis_run ADD CONSTRAINT analysis_run_failure_contract CHECK (
    (status='SUCCEEDED' AND error_code IS NULL)
    OR (status='FAILED' AND error_code IS NOT NULL AND error_code='DEFINITION_PARSE_FAILED'
        AND kind='DEFINITION_IMPORT' AND field_count=0 AND evidence='[]'::jsonb AND definition_import_id IS NULL)
);
