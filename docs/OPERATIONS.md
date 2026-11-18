# 운영 안내

아래 명령은 설치 디렉터리에서 실행한다.

## 상태와 로그

```sh
docker compose ps
docker compose logs --tail=100 api
docker compose logs --tail=100 edge
```

- **허브 연결·수집 관리**에서 연결 검사와 수집 이력 확인. 작업 상세에서 실행 단계, 처리량, 저장·미변경·실패 건수 확인
- 예약 수집의 주기·활성 상태를 연결별로 관리
- HTTP 202는 작업 접수 응답. 작업 이력에서 최종 결과 확인
- 로그를 외부에 전달할 때 계정·주소·비밀값 제거

## 설정 변경

1. `.env` 수정
2. `docker compose up -d --wait` 실행
3. `docker compose ps`와 접속 주소 확인

DB 비밀번호를 바꿀 때는 기존 DB 계정의 비밀번호도 같은 값으로 변경해야 한다. `.env`만 변경해도 기존 DB 계정의 비밀번호가 바뀌지는 않는다.

계정과 기관은 **권한·작업 이력**에서 관리한다. 전체 관리자는 로컬 계정 등록, 권한·기관 변경과 비밀번호 재설정을 수행할 수 있다.

허브 주소·인증 방식·수집 주기·자동 게시 설정은 관리자 화면에서 변경한다. 허용 주소는 `config/integration.properties`와 일치시킨다. 이 파일을 수정한 뒤에는 `docker compose restart api`로 반영한다.

## DB 백업

```sh
umask 077
mkdir -p backups
backup_file="backups/catalog-$(date -u +%Y%m%dT%H%M%SZ)-$$.dump"
(set -C; docker compose exec -T db pg_dump -U datacatalog -d datacatalog -Fc > "$backup_file") &&
  printf '백업 파일: %s\n' "$backup_file"
```

백업 파일과 `.env`는 공개 패키지에 포함하지 않고 별도 보관한다. 비밀 저장 파일을 사용하는 설치는 해당 파일도 같은 시점에 보관한다.

## DB 복원

복원 대상의 기존 데이터를 덮어쓰는 작업이다. `backup_file`에 복원할 파일 경로를 지정한다. 백업 형식 검사가 성공한 뒤 서비스를 중지하고, 복원이 성공한 경우에만 재기동한다.

```sh
backup_file=backups/복원할파일.dump
docker compose exec -T db pg_restore --list < "$backup_file" > /dev/null &&
docker compose stop api web edge &&
docker compose exec -T db pg_restore -U datacatalog -d datacatalog \
  --clean --if-exists --no-owner --no-privileges --exit-on-error < "$backup_file" &&
docker compose up -d --wait
```

복원 후 관리자 로그인, 데이터셋 수, 최근 게시 버전, 연결·예약 수집 설정을 확인한다.

## 소스 갱신

1. DB·설정 파일 백업
2. 변경된 소스와 배포 파일 반영
3. 아래 명령으로 이미지 재빌드·교체
4. 로그인·공개 조회·수집 이력 확인

```sh
docker compose build
docker compose up -d --wait
```

DB 구조 변경은 서버 기동 시 Flyway로 적용한다. 이전 버전으로 되돌릴 때는 해당 버전의 이미지와 같은 시점의 DB·설정 백업을 함께 사용한다.

## 기동 오류

| 증상 | 확인 항목 |
| --- | --- |
| 설정 값 누락 | `.env`의 `CATALOG_DB_PASSWORD` |
| 포트 사용 중 | 바인딩 주소·포트와 기존 서비스 |
| DB 인증 실패 | `.env`와 DB 계정 비밀번호 일치 여부 |
| 허브 연결 실패 | API 주소·경로·허용 대역·인증 방식 |
| 수집 후 공개 목록 없음 | 수집 결과·필수 항목·게시 상태 |
| 관리자 변경 요청 거절 | 로그인 세션·역할·기관·최신 버전 |

`docker compose down -v`는 DB 볼륨을 삭제한다. 일반 중지·갱신에는 사용하지 않는다.
