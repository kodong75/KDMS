# KDMS 실행 기록

KIS 와 같은 형식. 실행 결과 원문은 `runs/YYYYMMDD_HHMM_<단계>.txt`, 여기에는 경로와 요약을 적는다. 비밀번호는 어디에도 남기지 않는다.
클라우드 스레드는 노트북 DB 에 닿지 못하므로 실행 결과를 적지 않는다(지어내지 않는다).

```
## YYYY-MM-DD HH:MM · 단계 · 작업명 · Mac/노트북/클라우드
- 목적 / 환경
- 실행 명령·SQL(그대로)
- 결과(건수·시간)
- 오류 원문
- 원인 → 해결 → 재실행 결과
```

## 2026-10-01 · 1단계 · 골격 빌드 확인 · 클라우드
- 목적: 1단계 PR 의 빌드·단위 시험·라이선스 검사가 통과하는지(노트북 DB 없이).
- 환경: Linux, OpenJDK 21.0.11, Maven 3.9.11. 대상 PG 쪽은 클라우드 컨테이너의 임시 PostgreSQL 16.14 로 확인(노트북 PG 아님).
- 실행: `mvn -B package` → `mvn -o package`(오프라인) → `java -jar target/kdms.jar status` / `init` / `web`, `mvn -o test -Pintegration -Dtest=TargetSchemaIT`
- 결과: 단위 시험 31개 통과. 라이선스 허용 목록 밖 0, 라이선스 정보 없음 0. 임시 PG 에서 `test/sql/pg/00_create_kdms_db.sql` 2회 실행 성공, `init` 2회(없음 → 1, 1 → 1), `status` 대상 접속·버전 출력, TargetSchemaIT 2개 통과.
- 오류 원문: `No suitable driver found for jdbc:postgresql://…` (jar 실행 시 status)
- 원인 → 해결: 실행 jar 안에서 DriverManager 가 공용 스레드 풀의 클래스로더로 드라이버를 찾음 → 드라이버 클래스를 직접 생성해 연결(`kdms.config.Jdbc`) → 재실행 정상.
- 원천 MS-SQL 쪽 확인(SourceProbeIT, Debezium 실제 스트리밍)은 Mac 에서 해야 한다(docs/test-env.md).

## 2026-10-01 · 2단계 · 스키마 변환 빌드·대상 적용 확인 · 클라우드
- 목적: `kdms plan`·`kdms schema` 와 규칙 엔진이 KDMS_MOCK 시험 고정 카탈로그(KIS `11_schema_pitfalls.sql` 을 옮긴 것)로 KIS mock.sql 과 같은 타입의 DDL 을 만들고, PG 16 에 실제로 적용되는지.
- 환경: Linux, OpenJDK 21.0.11, Maven 3.9.11, 클라우드 컨테이너의 임시 PostgreSQL 16(노트북 PG 아님). 원천 MS-SQL 없음.
- 실행: `mvn -B package`, `mvn -o test -Pintegration -Dtest='TargetDdlIT,TargetSchemaIT' -Dkdms.config=<임시 설정>`
- 결과: 단위 시험 55개 통과(타입 비교 8테이블 62컬럼 일치 포함: mock.sql 7테이블 + KIS #23 의 file_attach), 라이선스 검사 통과(새 의존성 없음). TargetDdlIT 3개·TargetSchemaIT 2개 통과: 세 단계 DDL 적용, `format_type` 으로 읽은 타입 = 계획, `Kim01`/`kim01` UNIQUE 거부(B14), tinyint CHECK, 계산 컬럼 GENERATED 값(`'aa+ '` → 2, `.PDF` → `pdf`), 시퀀스 기본값 202600001, FK 거부, `--replace`·LOADING 거부, 실패 시 전부 되돌림.
- 원천 쪽(카탈로그 조회 SQL 이 실제 서버에서 시험 고정값과 같은지, `--scan`)은 Mac 에서 해야 한다(docs/test-env.md §8). 실행 결과를 지어내지 않는다.
