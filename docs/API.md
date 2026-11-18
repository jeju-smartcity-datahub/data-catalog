# API 안내

## 명세

- 전체 명세: [api/openapi.json](api/openapi.json)
- 호출 예제: `examples/`의 실행 파일
- 아래 공개 조회는 로그인 없이 사용
- 관리자 API는 로그인·권한·기관 범위에 따라 사용

## 공개 조회

| 메서드 | 경로 | 내용 |
| --- | --- | --- |
| GET | `/api/catalog/v1/datasets` | 공개 데이터셋 목록 |
| GET | `/api/catalog/v1/datasets/facets` | 검색 조건별 분류 집계 |
| GET | `/api/catalog/v1/datasets/{id}` | 공개 메타데이터 상세 |
| GET | `/api/catalog/v1/datasets/{id}/fields` | 필드 정보 |
| GET | `/api/catalog/v1/datasets/{id}/access` | 데이터 이용 경로 |
| GET | `/api/catalog/v1/datasets/{id}/versions` | 공개 게시 버전 |
| GET | `/api/catalog/v1/datasets/{id}/records` | 현재 게시본의 허브 데이터 조회 |
| GET | `/api/catalog/v1/datasets/{id}/records/download` | 조회 구간의 JSON·CSV 다운로드 |
| GET | `/api/catalog/v1/dcat` | DCAT JSON-LD·Turtle |
| GET | `/api/catalog/v1/dcat/download` | DCAT 파일 다운로드 |
| GET | `/api/docs/v1/datasets/{id}` | 소비자 API 문서 상태 |

`{id}`에는 목록 응답의 데이터셋 ID를 사용한다. 게시되지 않았거나 철회된 데이터는 공개 조회에서 제외한다.

```sh
BASE_URL=http://127.0.0.1:18184
curl --fail "$BASE_URL/api/catalog/v1/datasets?page=0&size=10"
curl --fail -H 'Accept: application/ld+json' "$BASE_URL/api/catalog/v1/dcat"
curl --fail -H 'Accept: text/turtle' "$BASE_URL/api/catalog/v1/dcat"
curl --fail "$BASE_URL/api/catalog/v1/dcat/download?format=jsonld" -o catalog.jsonld
```

`BASE_URL`은 설치한 카탈로그의 접속 주소로 지정한다. DCAT 목록은 `page`·`size`로 조회하며 `X-Total-Count`와 다음 페이지 `Link`를 제공한다.

레코드 조회에는 현재 게시본의 공개 필드와 유효한 허브 조회 연결이 필요하다. `limit`(1~1000)과 `offset`(0~10000)으로 구간을 지정한다. 다운로드 시 `format=json` 또는 `format=csv`를 사용한다.

## 관리자 API

1. `GET /api/admin/v1/auth`에서 로그인 사용 여부와 CSRF 정보 조회
2. 같은 쿠키 세션에서 로그인 요청
3. 변경 요청에 응답의 CSRF 헤더 적용
4. 수정·게시 요청은 명세에 따라 `If-Match`·`Idempotency-Key` 적용

연결 검사와 수집 요청의 HTTP 202는 작업 접수 상태다. 응답의 작업 ID로 `/api/admin/v1/sync-runs/{id}`를 조회하여 처리 단계(`phase`), 처리량(`processedCount`, `totalCount`), 저장·미변경·실패 건수 및 최종 결과를 확인한다.

## 오류 처리

| 상태 | 처리 |
| --- | --- |
| 401 | 로그인 상태 확인 |
| 403 | 역할·기관·CSRF 확인 |
| 409 | 처리 상태·중복 요청 내용 확인 |
| 412 | 최신 버전 재조회 후 수정 |
| 428 | `If-Match` 등 필수 조건 확인 |

데이터셋별 게시 메타데이터 JSON과 전체 카탈로그 DCAT 피드는 공개 API에서 각각 조회한다. 허브 레코드 조회 설정과 검사 결과는 관리자 API `/api/admin/v1/datasets/{id}/data-access`에서 관리한다.
