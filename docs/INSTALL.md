# 설치·실행

## 준비

- Linux x86_64
- Docker Engine과 Docker Compose v2
- 운영 권장: 4 vCPU·RAM 8 GB·여유 디스크 20 GB 이상
- 데이터 증가분과 소스 빌드 공간 별도 확보
- 이미지와 빌드 의존성을 내려받을 수 있는 네트워크
- 아래 명령은 공개 패키지의 최상위 디렉터리에서 실행
- 호스트의 Python·Java·Node.js 설치 불필요

## 1. 설정

```sh
test -f .env || cp .env.example .env
```

최초 설치 시 `.env`의 `CATALOG_DB_PASSWORD`에 DB 비밀번호를 입력한다. 재실행 시 기존 값을 유지한다. `.env`는 공개 저장소에 포함하지 않는다.

기본 접속 주소는 `http://127.0.0.1:18184`다. 다른 주소에서 접속할 때는 `.env`의 `CATALOG_BIND_ADDRESS`와 `CATALOG_HTTP_PORT`를 배포 환경에 맞춘다.

## 2. 빌드·기동

```sh
docker compose build
docker compose up -d --wait
docker compose ps
```

`db`·`api`·`web`·`edge`가 기동된다. 최초 기동 시 데이터베이스 구조가 생성된다. 공개 데이터셋은 관리자가 등록하고 게시한 뒤 목록에 표시된다.

## 3. 최초 관리자 생성

```sh
docker compose exec api java \
  -Dloader.main=kr.co.dtonic.datacatalog.bootstrap.LocalAccountProvisioner \
  -cp /app/server.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  --username admin --roles PLATFORM_ADMIN
```

화면에 표시되는 입력 안내에 따라 비밀번호를 입력한다. 비밀번호는 화면에 표시되지 않는다. 초기 관리자 ID는 `admin`이며 비밀번호는 설치자가 지정한다.

이미 관리자 계정을 생성한 설치에서는 이 단계를 생략한다.

## 4. 접속

- 사용자 화면: `http://127.0.0.1:18184/`
- 관리자 화면: `http://127.0.0.1:18184/admin/`
- 공개 API: `http://127.0.0.1:18184/api/catalog/v1/datasets`

관리자 화면에서 `admin`으로 로그인한다. 허브를 연결할 운영기관이 필요한 경우 **권한·작업 이력**의 기관 관리 화면에서 등록한다. 허브 연결·수집·게시 절차는 [관리자 매뉴얼](manuals/관리자매뉴얼.pdf)을 따른다.

## 5. 데이터허브 연결

`config/integration.properties`에 연결할 허브의 주소를 지정한다.

| 설정 | 입력 내용 |
| --- | --- |
| `catalog.integration.allowed-origins` | HTTPS 허브의 프로토콜·호스트·포트 |
| `catalog.integration.allowed-http-origins` | HTTP 허브의 프로토콜·호스트·포트 |
| `catalog.integration.pinned-addresses` | 호스트별 실제 접속 IP 목록을 담은 JSON 객체 |
| `catalog.integration.allowed-private-addresses` | 내부망 허브의 허용 IP |
| `catalog.integration.keti.records-endpoint-mappings` | KETI 메타데이터 기본 주소와 엔티티 기본 주소의 대응표(JSON 객체) |

설정 반영 후 서버를 다시 시작한다.

```sh
docker compose restart api
```

관리자 화면의 **허브 연결·수집 관리**에서 연결을 등록한다. KETI 유형은 메타데이터 기본 주소에 `/datasets`를 붙여 목록을 조회한다. 상세 응답에 데이터모델 ID가 있으면 `/datamodels/{id}`에서 필드 정의를 가져온다.

데이터 서비스 브로커의 주소가 별도인 경우 아래와 같이 기본 주소를 대응시킨다. 예시 주소를 설치 환경의 주소로 바꾸고, 두 서버 모두 허용 주소 및 고정 IP 설정에 등록한다. 동일 주소에서 제공하는 경우 기본값 `{}`을 사용한다.

```properties
catalog.integration.keti.records-endpoint-mappings={"https://metadata.example.org":"https://broker.example.org"}
```

### 인증 허브 연결 시 선택 설정

인증 없는 연결은 관리자 화면에서 인증 방식 **없음**을 선택한다. Bearer·API Key 인증이 필요한 허브는 HTTPS로 연결한다.

`mkdir -p private/hub-secrets`로 디렉터리를 만든 뒤 `hub-auth.json`에 아래 중 한 가지 형식으로 저장한다. `TOKEN_VALUE`·`KEY_VALUE`는 발급받은 값으로 바꾸고, API Key 헤더명은 허브 규격에 맞춘다.

| 인증 | 파일 내용 |
| --- | --- |
| Bearer | `{"type":"BEARER","token":"TOKEN_VALUE"}` |
| API Key | `{"type":"API_KEY","header":"X-API-Key","value":"KEY_VALUE"}` |

서버 프로세스의 UID/GID `10001:10001`이 읽을 수 있도록 소유권과 권한을 설정한다.

```sh
sudo chown 10001:10001 private/hub-secrets private/hub-secrets/hub-auth.json
sudo chmod 700 private/hub-secrets
sudo chmod 600 private/hub-secrets/hub-auth.json
```

다음 내용을 `compose.auth.yaml`로 저장한다.

```yaml
services:
  api:
    volumes:
      - ./private/hub-secrets:/run/catalog-secrets:ro,Z
```

```sh
docker compose -f compose.yaml -f compose.auth.yaml up -d --wait
```

관리자 화면의 비밀 참조 값은 확장자를 제외한 `hub-auth`로 입력한다. 이후 Compose 명령에도 두 파일을 함께 지정한다. 비밀 파일은 저장소·공개 패키지에 포함하지 않는다.

## 중지·재기동

```sh
docker compose stop
docker compose up -d --wait
```

상태·백업·갱신은 [운영 안내](OPERATIONS.md)를 따른다.
