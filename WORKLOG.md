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
- 결과: 단위 시험 55개 통과(타입 비교 7테이블 55컬럼 일치 포함), 라이선스 검사 통과(새 의존성 없음). TargetDdlIT 3개·TargetSchemaIT 2개 통과: 세 단계 DDL 적용, `format_type` 으로 읽은 타입 = 계획, `Kim01`/`kim01` UNIQUE 거부(B14), tinyint CHECK, 계산 컬럼 GENERATED 값(`'aa+ '` → 2, `.PDF` → `pdf`), 시퀀스 기본값 202600001, FK 거부, `--replace`·LOADING 거부, 실패 시 전부 되돌림.
- 원천 쪽(카탈로그 조회 SQL 이 실제 서버에서 시험 고정값과 같은지, `--scan`)은 Mac 에서 해야 한다(docs/test-env.md §8). 실행 결과를 지어내지 않는다.

## 2026-10-06 14:40 · 3단계 · 전체 적재·검증 확인 · 클라우드
- 목적: `kdms load`·`kdms verify` 가 실제 MS-SQL·PG 에서 동작하는지, 강제 종료 뒤 이어서 적재되는지, 검증이 차이를 잡는지.
- 환경: **클라우드 컨테이너**(노트북 아님). SQL Server 2019 Developer(Linux 컨테이너, 서버 콜레이션 Korean_Wansung_CI_AS)에 KIS `sql/10_mssql/10~12` + `test/sql/mssql/00·10·20` 으로 KDMS_MOCK 구성, 로그인 kodong_ms. 대상 PostgreSQL 16.15(컨테이너 안). OpenJDK 21.
- 실행 명령·결과 원문: `runs/20261006_1440_p3_cloud_load_verify.txt` (status → schema → load → verify → kill -9 재시작 → 변조 검출 → 종료 코드).
- 결과: 7개 테이블 48,047행, 검증 항목 30개 중 일치 30. `--throttle-ms 1000` 적재를 5초 뒤 `kill -9` → 다시 실행 시 남은 5개 구간만 적재, 검증 30/30. 대상 4건 변조(끝 공백·1ms·삭제·추가) → 각 PK 와 missing/extra/diff 검출, 종료 코드 5.
  단위 시험 79개, 통합 시험 9개(LoadVerifyIT 포함) 통과.
- 오류 원문: 기본 규칙(`nul_char: fail`)에서 `dbo.issuer` 구간 2 실패(T-L09, NUL 문자).
- 원인 → 해결: KIS 가 심은 NUL → `config/kdms-rules.yml` 에 `issuer_nm: { nul_char: replace }` (docs/load-verify.md §4) → 그 구간만 다시 적재, 검증 일치.
  검증에서 issuer 해시가 전부 다름 → NUL REPLACE 에 cp1252 이진 콜레이션을 써서 한글이 `?` 로 바뀜 → `Latin1_General_100_BIN2_UTF8` 로 바꿈 → 일치.
- 노트북 원천·PG 에서의 확인은 Mac 에서 해야 한다(docs/test-env.md §9).

## 2026-10-08 11:00 · 3단계 · 전체 적재·검증 확인 · Mac(노트북 DB)
- 목적: plan.md §6 3단계 완료 기준을 노트북 원천 MS-SQL·대상 PG 로 확인.
- 환경: Mac(Java 21) → 노트북 192.168.0.12 (MSSQL 1433 KDMS_MOCK, 로그인 kodong_ms / PG 5432 kdms, kdms_app). 원천 쓰기 없음.
- 실행: docs/test-env.md §9 ①~⑤. 결과 원문 `runs/*_p3_*.txt`(Mac 에서 올림).
- 결과: `schema` 문장 11개·테이블 7개(job_id 10) → `load` 7개 테이블 48,053행, 실패 0, 적재 뒤 DDL 8개 → `verify` 검증 항목 30개 중 일치 30(run_id 1).
  `load --reset --throttle-ms 1000` 을 8초 뒤 `kill -9` → 다시 `load`: 이미 적재된 테이블 4개 건너뜀, research_doc 끝난 구간 1개 건너뜀, 실패 0 → `verify` 30/30(run_id 3).
  통합 시험 LoadVerifyIT·SourceCatalogIT·TargetDdlIT 6개 통과.
- 오류 원문: `적재하지 않음: 작업 kdms_mock 이 없다. 먼저 kdms schema 로 대상 테이블을 만든다`
- 원인 → 해결 → 재실행: 2단계 `schema` 를 노트북 PG 에 아직 적용하지 않았음 → `schema` 실행 → `load` 정상.
  ③ 의 `종료 코드` 가 빈 값: 문서 명령이 bash 의 `PIPESTATUS` 를 써서 zsh 에서 빈 값 → zsh `pipestatus[1]` 로 문서 수정.
- 클라우드(48,047행)와 행 수가 6 다른 것은 노트북 원천 건수 차이로 추정(검증은 원천=대상 일치).
