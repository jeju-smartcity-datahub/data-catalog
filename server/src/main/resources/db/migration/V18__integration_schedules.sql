-- Persistent metadata collection schedules. No public catalog publication is scheduled.
CREATE TABLE integration.sync_schedule (
    sou_lnkg_id uuid PRIMARY KEY REFERENCES integration.source_connection(id),
    use_yn boolean NOT NULL DEFAULT false,
    cycl_sec_cnt integer NOT NULL CHECK (cycl_sec_cnt BETWEEN 60 AND 86400),
    next_excn_dt timestamptz,
    last_dmnd_dt timestamptz,
    last_excn_id uuid REFERENCES integration.sync_run(id),
    last_err_cd varchar(80),
    stng_user_id uuid NOT NULL,
    lck_ver_no bigint NOT NULL DEFAULT 1,
    crt_dt timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mdfcn_dt timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (use_yn = (next_excn_dt IS NOT NULL))
);
CREATE INDEX sync_schedule_due ON integration.sync_schedule(next_excn_dt, sou_lnkg_id) WHERE use_yn;
COMMENT ON TABLE integration.sync_schedule IS '연결별 메타데이터 정기 수집 설정';
COMMENT ON COLUMN integration.sync_schedule.stng_user_id IS '마지막 설정 관리자. 실행 권한이나 세션을 저장하지 않는다.';
