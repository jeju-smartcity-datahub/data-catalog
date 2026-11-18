-- Opt-in development authentication storage. No account or credentials are provisioned here.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';

CREATE TABLE identity.local_account (
    id uuid PRIMARY KEY,
    lgn_id varchar(80) NOT NULL UNIQUE CHECK (lgn_id ~ '^[a-z0-9][a-z0-9._-]{2,79}$'),
    pwd_hash varchar(60) NOT NULL CHECK (pwd_hash ~ '^\$2[aby]\$12\$[./A-Za-z0-9]{53}$'),
    stts_cd varchar(20) NOT NULL DEFAULT 'INACTIVE' CHECK (stts_cd IN ('ACTIVE', 'INACTIVE')),
    auth_ver_no bigint NOT NULL DEFAULT 0 CHECK (auth_ver_no >= 0),
    crt_dt timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mdfcn_dt timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE identity.local_account_role (
    acnt_id uuid NOT NULL REFERENCES identity.local_account(id) ON DELETE CASCADE,
    role_cd varchar(30) NOT NULL CHECK (role_cd IN ('PLATFORM_ADMIN', 'ORG_EDITOR', 'ORG_REVIEWER', 'ORG_PUBLISHER', 'ORG_AUDITOR')),
    PRIMARY KEY (acnt_id, role_cd)
);
CREATE TABLE identity.local_account_organization (
    acnt_id uuid NOT NULL REFERENCES identity.local_account(id) ON DELETE CASCADE,
    inst_id uuid NOT NULL REFERENCES identity.organization(id) ON DELETE RESTRICT,
    PRIMARY KEY (acnt_id, inst_id)
);
CREATE INDEX local_account_organization_inst_idx ON identity.local_account_organization(inst_id);

-- Changing credentials, status, roles or membership invalidates all previously issued sessions.
CREATE FUNCTION identity.advance_local_auth_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.auth_ver_no := OLD.auth_ver_no + 1;
    NEW.mdfcn_dt := CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;
CREATE TRIGGER local_account_auth_version BEFORE UPDATE ON identity.local_account
FOR EACH ROW EXECUTE FUNCTION identity.advance_local_auth_version();

CREATE FUNCTION identity.revoke_local_account_sessions() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        UPDATE identity.local_account SET auth_ver_no = auth_ver_no + 1 WHERE id = OLD.acnt_id;
    END IF;
    IF TG_OP = 'INSERT' OR (TG_OP = 'UPDATE' AND NEW.acnt_id <> OLD.acnt_id) THEN
        UPDATE identity.local_account SET auth_ver_no = auth_ver_no + 1 WHERE id = NEW.acnt_id;
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER local_account_role_revoke AFTER INSERT OR UPDATE OR DELETE ON identity.local_account_role
FOR EACH ROW EXECUTE FUNCTION identity.revoke_local_account_sessions();
CREATE TRIGGER local_account_organization_revoke AFTER INSERT OR UPDATE OR DELETE ON identity.local_account_organization
FOR EACH ROW EXECUTE FUNCTION identity.revoke_local_account_sessions();

-- An inactive institution cannot leave usable sessions, even if reactivated before the next request.
CREATE FUNCTION identity.revoke_local_institution_sessions() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.stts_cd IS DISTINCT FROM NEW.stts_cd THEN
        UPDATE identity.local_account SET auth_ver_no = auth_ver_no + 1
        WHERE id IN (SELECT acnt_id FROM identity.local_account_organization WHERE inst_id = NEW.id);
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER organization_local_auth_revoke AFTER UPDATE OF stts_cd ON identity.organization
FOR EACH ROW EXECUTE FUNCTION identity.revoke_local_institution_sessions();

COMMENT ON TABLE identity.local_account IS '로컬 계정. 개발·시험 local-auth 프로필에서만 사용하며 계정 자동 생성은 하지 않는다.';
COMMENT ON COLUMN identity.local_account.id IS '로컬 계정의 아이디. 서버 CatalogPrincipal 사용자 식별자.';
COMMENT ON COLUMN identity.local_account.lgn_id IS '로그인아이디. 소문자 ASCII 3~80자 정규화 유일값.';
COMMENT ON COLUMN identity.local_account.pwd_hash IS '비밀번호해시. BCrypt 비용 12의 60자 해시만 저장하며 원문은 저장하지 않는다.';
COMMENT ON COLUMN identity.local_account.stts_cd IS '상태코드. ACTIVE, INACTIVE; 기본 비활성.';
COMMENT ON COLUMN identity.local_account.auth_ver_no IS '인증버전번호. 계정·권한·기관 범위 변경 시 증가하며 기존 세션을 무효화한다.';
COMMENT ON COLUMN identity.local_account.crt_dt IS '생성일시.';
COMMENT ON COLUMN identity.local_account.mdfcn_dt IS '수정일시.';
COMMENT ON TABLE identity.local_account_role IS '로컬 계정 역할. 서버에 정의된 관리 역할만 부여한다.';
COMMENT ON COLUMN identity.local_account_role.acnt_id IS '계정아이디.';
COMMENT ON COLUMN identity.local_account_role.role_cd IS '역할코드. PLATFORM_ADMIN, ORG_EDITOR, ORG_REVIEWER, ORG_PUBLISHER, ORG_AUDITOR.';
COMMENT ON TABLE identity.local_account_organization IS '로컬 계정 기관 범위. 연결된 모든 기관은 활성 상태여야 한다.';
COMMENT ON COLUMN identity.local_account_organization.acnt_id IS '계정아이디.';
COMMENT ON COLUMN identity.local_account_organization.inst_id IS '기관아이디.';
