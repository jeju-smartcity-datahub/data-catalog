-- Idempotent publication fixture for the catalog integration test.
INSERT INTO identity.organization(id, cd, inst_nm) VALUES
('10000000-0000-0000-0000-000000000001', 'TEST-PROVIDER', '시험 제공기관') ON CONFLICT DO NOTHING;
INSERT INTO classification.category(id, cd, nm) VALUES
('20000000-0000-0000-0000-000000000001', 'TEST-CLASS', '환경') ON CONFLICT DO NOTHING;
INSERT INTO catalog.dataset(id, psn_inst_id, slug_nm) VALUES
('30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'test-metadata') ON CONFLICT DO NOTHING;
INSERT INTO catalog.dataset_revision(id, dtset_id, rvsn_no, stts_cd, ttl, expln, pvsn_inst_id, main_clsf_id, rls_rng_cd, mtdt_cn) VALUES
('40000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 1, 'APPROVED',
 '반복 등록 시험', '카탈로그 조회 시험용 메타데이터',
 '10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'PUBLIC', '{"fixture":true}') ON CONFLICT DO NOTHING;
INSERT INTO catalog.publication(id, dtset_id, rvsn_id, pstg_no, stts_cd, acs_stts_cd, pstg_dt) VALUES
('50000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001',
 1, 'PUBLISHED', 'METADATA_ONLY', '2026-10-16T00:00:00Z') ON CONFLICT DO NOTHING;
UPDATE catalog.dataset SET now_pstg_id = '50000000-0000-0000-0000-000000000001'
WHERE id = '30000000-0000-0000-0000-000000000001' AND now_pstg_id IS NULL;
