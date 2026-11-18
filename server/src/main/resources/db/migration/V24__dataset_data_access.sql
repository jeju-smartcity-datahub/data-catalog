CREATE TABLE delivery.dataset_data_access (
    dataset_id uuid PRIMARY KEY REFERENCES catalog.dataset(id),
    connection_id uuid NOT NULL REFERENCES integration.source_connection(id),
    enabled boolean NOT NULL DEFAULT false,
    mode varchar(20) NOT NULL CHECK (mode IN ('KETI_DATASET','NGSI_LD','JSON_ARRAY')),
    query_path varchar(500) NOT NULL,
    scope_field varchar(200) NOT NULL,
    scope_value varchar(300) NOT NULL,
    scope_parameter varchar(80) NOT NULL,
    fixed_query jsonb NOT NULL DEFAULT '{}',
    lock_version bigint NOT NULL DEFAULT 0,
    checked_connection_version bigint,
    checked_at timestamptz,
    updated_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (jsonb_typeof(fixed_query)='object')
);
CREATE TABLE delivery.data_access_event (
    id uuid PRIMARY KEY,
    dataset_id uuid NOT NULL REFERENCES catalog.dataset(id),
    actor_id uuid NOT NULL,
    action varchar(30) NOT NULL,
    configuration_version bigint NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE delivery.dataset_data_access IS '데이터셋별 조회 연결·고정 조건·검사 결과';
COMMENT ON TABLE delivery.data_access_event IS '데이터 조회 설정과 검사 이력';
