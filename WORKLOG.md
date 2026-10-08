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

## 2026-10-08 02:57 · 4단계 · 변경분 수집·반영(CDC) 확인 · 클라우드
- 목적: plan.md §6 4단계 완료 기준(원천 쓰기를 넣는 동안 적재 → 반영, 쓰기 중지 후 검증 일치)과 T-C 시험을 실제 MS-SQL CDC·PG 로 확인.
- 환경: **클라우드 컨테이너**(노트북 아님). SQL Server 2019 Developer CU32(Linux 컨테이너, SQL Agent 켬), KDMS_MOCK(DB 콜레이션 Korean_Wansung_CI_AS) CDC 7개 테이블, 로그인 kodong_ms(`20_grant_kdms_login.sql` 권한 + 서버 VIEW SERVER STATE). 대상 PostgreSQL 16.15. OpenJDK 21. Debezium 3.7.0.Final.
  **서버 콜레이션은 기본값(SQL_Latin1_General_CP1_CI_AS)**: 서버 콜레이션을 Korean_Wansung_CI_AS 로 설치한 Linux 컨테이너는 CDC 캡처 Job 이 `msdb.dbo.cdc_jobs` 없음 → 만든 뒤에도 `Could not load the DLL replcmds` 로 실패해서 다시 만들었다(Linux 컨테이너 문제로 보이며 노트북 Windows 설치와는 다르다).
- 원천 쓰기: `test/sql/mssql/30_writes.sql` 을 sa 로 실행(시험 환경).
- 실행 명령·결과 원문: `runs/20261008_0257_p4_cloud_e2e.txt`(reset → sync → 쓰기 300초 → 쓰기 중 load → sync kill -9 → 재시작 → 쓰기 끝 → SIGTERM → sync --drain → verify → 관리 테이블 → post-load DDL),
  `runs/20261008_0250_p4_cloud_tc10.txt`(보존 기간 초과), `runs/20261008_0256_p4_cloud_drain_writes.txt`(쓰기 도중 drain), `runs/20261008_0306_p4_cloud_tc09.txt`(캡처 Job 중지).
- 결과: 쓰기 8,000회(입력·수정·삭제·PK 변경 234·LOB 미변경 수정 407·입력 직후 삭제 394·여러 테이블 트랜잭션 312·3초 트랜잭션 16) 동안 적재 65,269행, 수집·반영 13,109건, 반영 대기 0, change_log 0행.
  `kill -9` 뒤 다시 시작해 오프셋 다음부터 이어 받음. `--drain` 6초, **검증 항목 30개 중 일치 30**(run_id 7). post-load DDL 8개 적용.
  앞선 두 번의 같은 시험(쓰기 150·180초, 적재 전 수집 중·적재 뒤 반영 중 kill -9)도 30/30. 기록용 시험의 kill -9 는 적재(1초)가 끝난 직후 반영 중이었다(적재 도중 kill 은 3단계에서 확인).
  보존 기간 초과: 캡처 인스턴스를 오프셋 뒤 LSN 까지 `sp_cdc_cleanup_change_table` → `kdms sync` 종료 코드 5 와 "kdms reset --yes 뒤 … 전체 적재부터" 안내 → `reset --yes` → 처음부터 다시 30/30.
  캡처 Job 60초 중지(T-C09, 쓰기 중): 지연이 19초 → 59초로 늘고 "원천 캡처 Job 이 N초째 로그를 읽지 않음", 39초부터 "원천 로그 REPLICATION 대기" 표시 → 다시 시작 10초 안에 지연 0 → drain → 30/30.
  단위 시험 90개 통과.
- 오류 원문 → 원인 → 해결 → 재실행:
  1. `NoClassDefFoundError: io/debezium/spi/storage/DefaultOffsetStorageReader` → `debezium-storage-jdbc` 가 `debezium-storage-common` 을 provided 로만 선언 → 의존성 추가(Apache-2.0, licenses.md) → 엔진 시작.
  2. 재시작 때 `ClassCastException: class java.lang.Integer cannot be cast to class java.lang.Long` (SqlServerOffsetContext$Loader) → storage-jdbc 3.7.0 이 저장한 오프셋의 event_serial_no 를 Integer 로 읽음 → `JdbcOffsetBackingStore` 를 상속해 읽은 Integer 를 Long 으로 바꾸는 `KdmsOffsetStore` 를 ServiceLoader(`kdms-jdbc`)로 등록(포크 없음, cdc.md §6) → 재시작 정상.
  3. 처음 쓴 `--drain` 이 쓰기 도중에도 끝나 검증 불일치(27/30) → "캡처 Job 이 마지막 커밋 뒤 시작한 훑기" 를 기준으로 삼아 그 훑기가 잡은 커밋에 스스로 만족함 → 따라잡은 순간의 원천 시각 뒤에 시작한 훑기를 기준으로 바꾸고 확인 뒤 다시 조회 → 쓰기 도중 시작한 drain 이 쓰기 끝 7초 뒤 끝나고 30/30.
  4. SIGTERM 뒤 프로세스가 90초 남음 → 종료 훅이 System.exit 에서 멈춘 main 스레드를 join → 작업 끝 latch 로 기다리게 바꿈 → 1초 안에 끝남.
  5. 캡처 Job 을 멈춰도 지연이 0.0초로 표시 → 지연 계산이 캡처된 커밋(`lsn_time_mapping`)만 봄 → 캡처 Job 의 마지막 로그 훑기가 15초보다 오래되면 그 시간을 지연으로 쓰고 경고 → 위 T-C09 결과.
- 통합 시험(같은 클라우드 DB): TargetDdlIT·TargetSchemaIT 통과. SourceCatalogIT(NUL 행)·LoadVerifyIT(rating_id 5 변조 검출) 는 실패 — 쓰기 시험이 원천 KDMS_MOCK 의 해당 행을 바꾸거나 지웠기 때문(원천에서 rating_id 5 없음, NUL 행 0 확인). 코드 문제가 아니라 시험 데이터가 바뀐 것이라 test-env.md §10 에 "쓰기 시험 뒤에는 00_restore REPLACE=1 로 되돌린다" 를 적었다.
- 노트북 원천·PG 에서의 확인은 Mac 에서 해야 한다(docs/test-env.md §10). 실행 결과를 지어내지 않는다.

## 2026-10-08 12:26 · 4단계 · 변경분 수집·반영(CDC) 확인 · Mac(노트북 DB)
- 목적: plan.md §6 4단계 완료 기준(원천 쓰기를 넣는 동안 적재 → 반영, 쓰기 중지 후 검증 일치)을 노트북 원천 MS-SQL·대상 PG 로 확인.
- 환경: Mac(Java 21, 브랜치 claude/stage4-cdc-fxm28p 3117b14) → 노트북 192.168.0.12 (MSSQL 1433 KDMS_MOCK, 로그인 kodong_ms, SQL Agent 실행 중 / PG 5432 kdms, kdms_app). 원천 쓰기는 노트북 PowerShell 7 `Invoke-KdmsSql.ps1 -File test/sql/mssql/30_writes.sql -Var @{ DURATION_SEC = '300' }`.
- 실행: docs/test-env.md §10 순서. 결과 원문 `runs/20261008_1226_p4_*.txt`(Mac 에서 올림, 노트북 쓰기 기록은 노트북에만 있음).
  build → `reset --yes` → `schema --replace`(문장 11개, job_id 10) → `sync`(워터마크 00000060:00001f68:0003) → 노트북 쓰기 300초 시작 → `load --throttle-ms 2000` → 적재 중 `pkill -9 -f 'kdms.jar sync'` → `sync` 다시 시작 → 쓰기 끝 → Ctrl+C → `sync --drain` → `verify` → `schema --phase post-load`.
- 결과: 적재 7개 테이블 48,535행, 실패 0, 적재 뒤 DDL 은 미룸. kill -9 전 수집 1,132·반영 288(적재 전 테이블 변경은 대기), 다시 시작 때 "저장된 오프셋 다음부터 이어 받는다" → 수집·반영 9,688건, 반영 대기 0.
  `--drain` 7초, 종료 코드 0. **검증 항목 30개 중 일치 30**, 테이블 7개 일치(run_id 6), 종료 코드 0. post-load DDL 8개 적용.
- 오류: 없음. 손 실수 하나: 다시 시작한 sync 를 다른 창에서 돌려 `S` 가 없어 `runs/_p4_sync2.txt` 로 저장 → 이름만 바꿈(내용 영향 없음).
- 관찰(고치지 않음, 5단계 전환 화면에서 다룬다): 적재 전 첫 수집 때 지연이 348~369초로 표시 → 아직 반영한 변경이 없으면 워터마크 LSN 의 커밋 시각부터 재는데, 그 LSN 은 스트리밍 시작 전 마지막 커밋이라 원천이 조용했던 시간까지 지연에 들어간다. 반영이 시작되자 0.6초로 정상.
- 고침: `sync` 시작 줄의 워터마크 기록 시각이 UTC(03:30)로 찍혀 다른 줄(지역 시각 12:30)과 달라 보임 → 지역 시각대로 바꿔 찍는다(SyncRunner, 단위 시험 통과, DB 재실행은 하지 않음).

## 2026-10-08 04:10 · 5단계 · 전환·화면·CLI 확인 · 클라우드
- 목적: plan.md §6 5단계 완료 기준(시나리오 S1~S4 통과 + 전환 소요 시간 보고)을 실제 MS-SQL CDC·PG 로 확인. 시나리오 정의는 docs/cutover.md §6.
- 환경: **클라우드 컨테이너**(노트북 아님). 4단계 클라우드 기록과 같은 구성(SQL Server 2019 CU32 Linux 컨테이너, KDMS_MOCK CDC 7개 테이블, 로그인 kodong_ms / PostgreSQL 16.15 kdms_app / OpenJDK 21). KIS 00_login_mig 가 sa 를 끄므로 시험용 sysadmin 로그인을 따로 만들어 원천 쓰기(`test/sql/mssql/30_writes.sql`, sqlcmd `-I`)에 썼다.
- 실행 명령·결과 원문:
  `runs/20261008_0410_p5_cloud_scenarios.txt` (S1·S2·S3: 매번 reset → schema --replace → sync → 쓰기 → 쓰기 중 load → 쓰기 끝 → sync 중지 → cutover --yes),
  `runs/20261008_0424_p5_cloud_s4.txt` (S4, 전환 뒤 대상에 새 행), `runs/20261008_0425_p5_cloud_web.txt` (웹 화면 버튼으로 sync → load → cutover).
- 결과:
  - S1 정상 전환(쓰기 120초): 마지막 반영 7.1초 · UNIQUE·인덱스 8개 · 검증 30/30(run_id 2) · IDENTITY 5개·SEQUENCE 1개 setval · FK 2개 · **소요 시간(예상 다운타임) 8.8초**, 작업 DONE.
  - S2 대상 issuer 한 행 변조 → [4/6] 검증 29/30, 차이 행 diff 1(PK 1) → **exit 5, FK 0개(전환하지 않음)**, 작업 FAILED → `load --reset -t dbo.issuer` → cutover 다시 30/30, 8.6초, DONE.
  - S3 쓰기가 남은 채 `cutover --max-wait 15` → **exit 6**(17.3초, 따라잡지 못함) → 쓰기 끝 → cutover 를 [4/6] 검증 중 `kill -9` → status 는 CUTOVER·cutover RUNNING → cutover 다시 처음 단계부터 30/30, 8.9초, DONE(UNIQUE·인덱스는 "이미 있어 건너뜀 8개").
  - S4(S3 뒤): 새 user_id 1172 · hist_id 23413 · seq_doc_no 202605224 = 원천 IDENT_CURRENT/current_value(1171·23412·202605223) 다음 값, 5개 IDENTITY 모두 MAX < 다음 값, 없는 issuer_id 입력은 `fk_rating_issuer` 위반으로 막힘(입력은 ROLLBACK).
  - 웹 버튼: 토큰 없는 POST 403, 같은 버튼 두 번 409, sync·load·cutover 종료 코드 0, 화면에서 띄운 sync 를 전환이 먼저 멈춤, 검증 30/30, 7.7초, DONE.
  - 4단계 이월(적재 전 지연 과대): 적재 전 첫 줄 지연 0.0초(S1 04:19:49, S2 04:22:06). 반영할 변경이 있는 테이블의 가장 오래된 대기 변경 시각부터 잰다(cdc.md 지연 정의).
  - 단위 시험 102개 통과.
- 오류 원문 → 원인 → 해결 → 재실행:
  1. sync 를 다시 시작한 뒤 cutover 의 마지막 반영이 "스트리밍 시작 전" 에서 끝나지 않음 → 원천에 새 커밋이 없으면 Debezium 이 첫 이벤트를 주지 않아 스트리밍 위치가 비어 있음 → 시작 때 저장된 오프셋의 커밋 LSN 으로 위치를 채우고 drain 조건을 그 위치로 봄 → 반영 대기 0 으로 끝남.
  2. `kdms cutover`(--yes 없음)가 설정 파일 오류 1 로 끝남(CliTest) → --yes 확인을 설정 읽기보다 먼저 → 4.
  3. `kdms web --port` 가 무시돼 시험이 8080 충돌(`PortInUseException`) → SpringApplicationBuilder.properties 가 application.yml 보다 우선순위가 낮음 → 실행 인자 `--server.port=` 로 넘김 → 통과.
  4. 전환 요약 표의 열이 한글 단계 이름에서 어긋남 → 한글을 2칸으로 세는 pad → 정렬됨. "원천 마지막 변경" 이 초 없이 찍힘 → HH:mm:ss 형식.
  5. 시험 스크립트의 S4 SQL `syntax error at or near "INTO"`(FROM 안의 INSERT … RETURNING) → CTE 로 고쳐 S3 뒤에 다시 실행(코드 문제 아님).
- 노트북 원천·PG 에서의 확인은 Mac 에서 해야 한다(docs/test-env.md §11, 먼저 00_restore REPLACE=1 로 원천 되돌리기). 실행 결과를 지어내지 않는다.

## 2026-10-08 15:44 · 5단계 · 전환·화면·CLI 확인 · Mac(노트북 DB)
- 목적: plan.md §6 5단계 완료 기준(S1 정상 전환 + 전환 소요 시간, S4 전환 뒤 새 입력, 화면)을 노트북 원천 MS-SQL·대상 PG 로 확인. S2·S3 은 클라우드 기록(위)으로 갈음.
- 환경: Mac(Java 21, 브랜치 claude/stage5-cutover-jdq5mm e863278) → 노트북 192.168.0.12 (MSSQL 1433 KDMS_MOCK Korean_Wansung_CI_AS, 로그인 kodong_ms, SQL Agent Running / PG 16.15 5432 kdms, kdms_app). Mac 창 main(빌드·load·cutover·status), sync(kdms sync), sub(kdms web).
- 실행: docs/test-env.md §11 순서. 노트북 `00_restore`(REPLACE=1)·`10_enable_cdc`·`20_grant` 모두 exit 0 → Mac build(단위 시험 102 통과) → `reset --yes` → `schema --replace`(job_id 10) → `web` → `sync`(워터마크 0000002a:00004c78:0003) → 노트북 `30_writes.sql` 300초 → 쓰기 중 `load --throttle-ms 2000` → 쓰기 끝 → sync Ctrl+C → `cutover --yes` → `status` → 노트북 S4 psql.
  결과 원문: Mac `runs/20261008_1547_p5_*.txt`(build·reset·schema·sync·load·cutover·status, Mac 에서 올림), `runs/20261008_1603_p5_s4.txt`(노트북 출력을 채팅으로 받아 옮김). 노트북 원문 `runs\20261008_154[45]_p5_*.txt`, `runs\20261008_1555_p5_writes.txt`, `runs\p5_s4.txt` 는 노트북에만 있다.
- 결과:
  - 적재 전 첫 진행 줄 `지연 0.0초`(원천 마지막 변경 8분 전) → 4단계 이월(348초 과대 표시) 해결 확인.
  - 적재 7개 테이블 48,576행, 실패 0(쓰기와 겹침). 수집·반영 9,572건.
  - **S1 cutover 종료 코드 0**: 마지막 반영 8.1초 · UNIQUE·인덱스 8개 0.6초 · **검증 30/30(run_id 7)** 3.1초 · IDENTITY 5개·SEQUENCE 1개 setval 0.5초 · FK 2개 0.3초 · **소요 시간(예상 다운타임) 13.0초**. status 작업 DONE, cutover 1 DONE.
  - **S4**: 새 user_id 1162(원천 1161 다음), nextval 202605226(원천 202605225 다음), issuer_id -1 입력은 `fk_rating_issuer` 위반. ROLLBACK.
  - 화면(sub): 원천·대상 접속됨, 관리 스키마 버전 3, CDC 캡처 테이블 7개, 테이블 대기 → 적재 → 전환 단계 표시.
- 오류: 없음. 관찰: `load` 끝 안내가 4단계 방식(`schema --phase post-load`, `다음: kdms verify`)이라 혼동 → CDC 모드면 "kdms cutover 가 마지막 반영 뒤 적용", "다음: … kdms cutover --yes" 로 고침(437d1dd, 단위 시험 102 통과, DB 재실행 안 함).
- 다운타임 13.0초가 클라우드(8.8초)보다 긴 것은 Mac↔노트북 네트워크 왕복 때문으로 추정(측정 안 함).
