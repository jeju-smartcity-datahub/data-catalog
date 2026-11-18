# 데이터 카탈로그 서버

데이터셋 수집·게시, 표준사전 관리 및 공개 API를 제공한다. Java 21과 전자정부 표준프레임워크 5.0.1을 사용하며, 데이터는 PostgreSQL에 저장한다.

## 빌드 및 시험

서버 디렉터리에서 Maven Wrapper를 실행한다. 통합시험은 Docker에서 PostgreSQL 17.11을 기동한다.

```sh
./mvnw verify
```

Windows에서는 `mvnw.cmd verify`를 사용한다.

## 관련 문서

- [설치 가이드](../docs/INSTALL.md)
- [API 명세](../docs/API.md)
- [운영 가이드](../docs/OPERATIONS.md)
