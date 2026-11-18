CREATE TABLE integration.publication_policy (
    connection_id uuid PRIMARY KEY REFERENCES integration.source_connection(id) ON DELETE RESTRICT,
    enabled boolean NOT NULL DEFAULT false,
    department varchar(200), license varchar(500), keywords jsonb NOT NULL DEFAULT '[]',
    default_category_id uuid REFERENCES classification.category(id) ON DELETE RESTRICT,
    actor_id uuid NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK(jsonb_typeof(keywords)='array')
);
CREATE TABLE integration.publication_batch (
    id uuid PRIMARY KEY, connection_id uuid NOT NULL REFERENCES integration.source_connection(id),
    actor_id uuid NOT NULL, key_hash varchar(64) NOT NULL, request_hash varchar(64) NOT NULL,
    mode varchar(10) NOT NULL CHECK(mode IN ('MANUAL','AUTO')), policy_snapshot jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(connection_id,actor_id,key_hash)
);
CREATE TABLE integration.publication_result (
    batch_id uuid NOT NULL REFERENCES integration.publication_batch(id),
    candidate_id uuid NOT NULL REFERENCES integration.source_metadata(id),
    result jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(batch_id,candidate_id)
);
CREATE TABLE integration.auto_publication_task (
    id uuid PRIMARY KEY, connection_id uuid NOT NULL REFERENCES integration.source_connection(id),
    candidate_id uuid NOT NULL REFERENCES integration.source_metadata(id), source_hash varchar(64) NOT NULL,
    policy_version bigint NOT NULL, connection_version bigint NOT NULL,
    status varchar(12) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','RUNNING','DONE','FAILED')),
    lease_until timestamptz, error_code varchar(80), message varchar(1000), created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(candidate_id,source_hash,policy_version,connection_version)
);
CREATE INDEX auto_publication_pending_idx ON integration.auto_publication_task(status,created_at);
-- Public Data Portal subject categories. Existing customer classifications are never changed.
INSERT INTO classification.category(id,cd,nm,sort_seq,expln)
SELECT ('c2300000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,'PUBLIC-'||lpad(n::text,2,'0'),nm,n,
       '공공데이터포털 분야 분류'
FROM (VALUES (1,'교육'),(2,'국토관리'),(3,'공공행정'),(4,'재정금융'),(5,'산업고용'),(6,'사회복지'),
 (7,'식품건강'),(8,'문화관광'),(9,'보건의료'),(10,'재난안전'),(11,'교통물류'),(12,'환경기상'),
 (13,'과학기술'),(14,'농축수산'),(15,'통일외교안보'),(16,'법률')) AS seed(n,nm)
WHERE NOT EXISTS(SELECT 1 FROM classification.category c WHERE c.nm=seed.nm OR c.cd='PUBLIC-'||lpad(seed.n::text,2,'0'))
ON CONFLICT DO NOTHING;
