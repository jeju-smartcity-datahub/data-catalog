ALTER TABLE integration.sync_run
    ADD COLUMN phase_cd varchar(40) NOT NULL DEFAULT 'PREPARING',
    ADD COLUMN tot_itm_cnt integer,
    ADD COLUMN prgrs_dt timestamptz,
    ADD COLUMN prcs_itm_cnt integer NOT NULL DEFAULT 0,
    ADD COLUMN save_itm_cnt integer NOT NULL DEFAULT 0,
    ADD COLUMN unchg_itm_cnt integer NOT NULL DEFAULT 0,
    ADD COLUMN fail_itm_cnt integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT sync_run_phase_check CHECK (phase_cd IN
        ('PREPARING','CHECKING_CONNECTION','FETCHING_LIST','FETCHING_DETAILS','SAVING_RESULTS','COMPLETED','FAILED','RETRY_WAIT')),
    ADD CONSTRAINT sync_run_progress_count_check CHECK (
        (tot_itm_cnt IS NULL OR tot_itm_cnt >= 0) AND prcs_itm_cnt >= 0 AND save_itm_cnt >= 0
        AND unchg_itm_cnt >= 0 AND fail_itm_cnt >= 0 AND prcs_itm_cnt=save_itm_cnt+unchg_itm_cnt+fail_itm_cnt);

UPDATE integration.sync_run SET phase_cd=CASE
    WHEN stts_cd IN ('SUCCEEDED','PARTIAL_FAILED') THEN 'COMPLETED'
    WHEN stts_cd='FAILED' THEN 'FAILED'
    WHEN stts_cd='RETRY_WAIT' THEN 'RETRY_WAIT'
    WHEN stts_cd='RUNNING' THEN CASE WHEN type_cd='CHECK' THEN 'CHECKING_CONNECTION' ELSE 'FETCHING_LIST' END
    ELSE 'PREPARING' END,
    prgrs_dt=COALESCE(end_dt,bgng_dt,crt_dt);

UPDATE integration.sync_run r SET prcs_itm_cnt=s.processed,save_itm_cnt=s.saved,
    unchg_itm_cnt=s.unchanged,fail_itm_cnt=s.failed
FROM (SELECT sync_excn_id,count(*)::integer AS processed,
    count(*) FILTER (WHERE stts_cd='CANDIDATE')::integer AS saved,
    count(*) FILTER (WHERE stts_cd='UNCHANGED')::integer AS unchanged,
    count(*) FILTER (WHERE stts_cd='FAILED')::integer AS failed
    FROM integration.sync_item GROUP BY sync_excn_id) s
WHERE r.id=s.sync_excn_id;
