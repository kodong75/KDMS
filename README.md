# KDMS
MS-SQL 2019 → PostgreSQL 16 미니 DMS(Database Migration Service, 데이터 이관 서비스). 폐쇄망 금융권용 단일 실행 jar.

- 계획: [docs/plan.md](docs/plan.md) (현재 2단계: 스키마 변환, [docs/schema-conversion.md](docs/schema-conversion.md))
- 오픈소스 라이선스: [docs/licenses.md](docs/licenses.md)
- 시험 환경 준비·실행 방법: [docs/test-env.md](docs/test-env.md)
- 실행 기록: [WORKLOG.md](WORKLOG.md), 결과 원문 `runs/`

## 빌드·실행 (Java 21)

```bash
./mvnw -B package                       # target/kdms.jar (단위 시험·라이선스 검사 포함)
cp .env.example .env                    # 비밀번호 채우기
cp config/kdms.example.yml config/kdms.yml
java -jar target/kdms.jar status        # 원천·대상 접속, 버전, CDC·관리 스키마 상태
java -jar target/kdms.jar init          # 대상 PG 에 관리 스키마 kdms 생성(재실행 안전)
java -jar target/kdms.jar plan          # 원천 카탈로그 + 변환 규칙 → 보고서·DDL(out/<작업>/). 아무 DB 도 안 바꾼다
java -jar target/kdms.jar schema        # 위 DDL(적재 전 단계)을 대상 PG 에 적용, 작업 등록
java -jar target/kdms.jar web           # 웹 화면 http://127.0.0.1:8080
```

## 구조

| 경로 | 내용 |
|---|---|
| `src/main/java/kdms/` | 패키지별 책임은 plan.md §4.1 (`config`, `catalog`, `rules`, `ddl`, `load`, `cdc`, `apply`, `verify`, `cutover`, `state`, `web`, `cli`) |
| `src/main/resources/db/kdms-schema.sql` | 관리 테이블(대상 PG `kdms` 스키마) |
| `src/main/resources/kdms-rules.yml` | 변환 규칙 기본값(plan.md §5) |
| `config/` | 작업 설정 예시, 작업별 규칙 예시, 라이선스 허용 목록 |
| `test/sql/` | 노트북에서 사람이 실행하는 시험 준비 SQL(MS-SQL 복원·CDC·권한, PG DB·역할) |
