# 데이터 카탈로그 (D.CATALOG)

데이터 카탈로그는 여러 데이터허브의 데이터셋을 한 곳에서 검색하고, 각 데이터셋의 제공기관·필드 정의·이용 방법을 확인하는 서비스입니다. 관리자는 허브별로 메타데이터를 수집하고 게시합니다. 게시한 메타데이터는 웹 화면과 DCAT API(JSON-LD, Turtle)에서 제공합니다.

## 구성

| 디렉터리 | 구성 요소 | 주요 기능 |
| --- | --- | --- |
| `web/` | 사용자 웹 · Next.js | 지역·분류별 검색, 데이터셋 상세, 제공 API와 데이터 미리보기 |
| `admin/` | 관리자 웹 · React, Vite | 허브 등록, 수집·게시, 분류·표준사전 관리, 작업 이력 조회 |
| `server/` | 응용 서버 · Java 21, 전자정부 표준프레임워크 | 허브 연계, 권한 관리, 카탈로그 API, PostgreSQL 스키마 관리 |

## 설치 및 실행

Linux x86_64 환경에서 Docker Engine과 Docker Compose v2를 사용합니다. 최초 빌드 시 컨테이너 이미지와 의존성을 내려받을 수 있어야 합니다.

1. 저장소 최상위 디렉터리에서 `.env.example`을 `.env`로 복사합니다.
2. `.env`의 `CATALOG_DB_PASSWORD`에 데이터베이스 비밀번호를 지정합니다.
3. 다음 명령으로 빌드하고 실행합니다.

```sh
docker compose build
docker compose up -d --wait
```

| 구분 | 기본 접속 주소 |
| --- | --- |
| 사용자 화면 | `http://127.0.0.1:18184/datasets` |
| 관리자 화면 | `http://127.0.0.1:18184/admin/` |

최초 설치 시 [설치 가이드](docs/INSTALL.md)에 따라 `admin` 계정을 생성하고 비밀번호를 지정합니다. 초기 분류는 공공데이터포털 표준 분야 16개입니다. 기관과 데이터허브는 설치 후 관리자 화면에서 등록합니다.

## 관련 문서

| 문서 | 내용 |
| --- | --- |
| [설치 가이드](docs/INSTALL.md) | 환경 설정, 계정 생성, 데이터허브 연결 |
| [운영 가이드](docs/OPERATIONS.md) | 서비스 상태 확인, 로그 조회, 백업 및 복원 |
| [시스템 구성](docs/ARCHITECTURE.md) | 서비스 구성과 데이터 처리 절차 |
| [API 가이드](docs/API.md) · [OpenAPI 명세](docs/api/openapi.json) | 조회 경로, 요청 매개변수, 응답 형식 |
| [사용자 매뉴얼](docs/manuals/사용자매뉴얼.pdf) · [관리자 매뉴얼](docs/manuals/관리자매뉴얼.pdf) | 화면별 사용 방법 |
| [호출 예제](examples/catalog.curl.sh) | cURL을 이용한 공개 API 조회 |

## 빌드 및 시험

로컬에서 소스를 빌드하려면 Node.js 22.13 이상과 JDK 21이 필요합니다. 서버 통합시험은 Docker에서 PostgreSQL을 실행합니다.

```sh
npm ci --ignore-scripts
npm run install:apps
npm run typecheck
npm run lint
npm run build:web
npm run build:admin
(cd server && sh ./mvnw --batch-mode verify)
```

## 라이선스

Copyright 2026 디토닉. 데이터 카탈로그 소스코드에는 [Apache License 2.0](LICENSE)이 적용됩니다. 외부 라이브러리와 글꼴의 라이선스는 [저작권 고지](NOTICE)와 [외부 구성요소 목록](THIRD_PARTY_NOTICES.md)에서 확인할 수 있습니다. 구성요소별 버전과 의존 관계는 [SBOM](sbom/)에 수록했습니다.
