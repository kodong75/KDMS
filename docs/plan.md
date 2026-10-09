# KDMS 계획 (개정 2)

> 상태: 진행 중 · 최종 갱신: 2026-10-09 · a43a5f8 · 근거: WORKLOG, PR 이력

MS-SQL 2019 → PostgreSQL 16 미니 DMS(Database Migration Service, 데이터 이관 서비스).
폐쇄망 금융권에서 실행 중 외부 다운로드 없이 돌아가는 단일 jar 를 만든다.

- 개정 이력: 초안 1 작성 2026-10-01, PR #1 머지(2026-10-01)로 1단계부터 진행. 단계 PR(#4·#8·#9·#10)이 본문 일부를 고쳤다. 개정 2(2026-10-09): 단계 상태·완료 근거(§6), 결정을 [decisions.md](decisions.md) 로, 관리 테이블을 [database.md](database.md) 로, 작업 규칙을 [CLAUDE.md](../CLAUDE.md) 로 옮김.
- 이 문서는 KIS(`kodong75/KIS`, 읽기 전용)의 `docs/plan.md`, `zero-downtime.md`, `zero-downtime-rehearsal.md`, `issues.md`, `appcompat.md`, `normalization.md`, `datatype-pitfalls.md`, `sql/30_verify` 를 읽고 옮겨 왔다. 작업 규칙(고객사 명칭 금지, KIS 읽기 전용, 비밀번호, 브랜치·PR)은 [CLAUDE.md](../CLAUDE.md) §2·§4.
- 문서 목록 [README.md](README.md), 결정 [decisions.md](decisions.md), 이슈 [issues.md](issues.md), 오픈소스와 라이선스 [licenses.md](licenses.md).

---

## 1. 무엇을 만드나

```
                 ┌──────────────────────── kdms.jar (Spring Boot, 단일 실행 파일) ────────────────────────┐
MS-SQL 2019      │                                                                                        │   PostgreSQL 16
 (원천)          │  ① 스키마 변환   ② 전체 적재기(COPY, 병렬)   ③ CDC 수집(Debezium Embedded)   ④ 반영기   │   (대상)
  테이블 ────────┼──────────────────────────────────────────────────────────────────────────────────────────┼──> 대상 테이블
  CDC 변경 테이블┼──> ③ ──> kdms.change_log(대상 PG 에 임시 저장) ──> ④ ──────────────────────────────────────┼──> 대상 테이블
                 │  ⑤ 검증(건수·합계·해시)    ⑥ 상태 저장(워터마크·진행률 = 대상 PG 의 kdms 스키마)            │
                 │  화면: 내장 웹(localhost)  /  CLI(Command Line Interface, 명령줄) 모드                      │
                 └────────────────────────────────────────────────────────────────────────────────────────┘
```

이관 순서(KIS:docs/zero-downtime.md §1 과 같다):

```
① 워터마크 기록 ─> ② 전체 적재(수 시간, 원천은 계속 쓰임) ─> ③ 변경분 반복 반영(지연 수 초~수 분)
                                                     ─> ④ 원천 쓰기 중지 ─> ⑤ 마지막 반영 ─> ⑥ 검증 ─> ⑦ 전환
                                                        └──────────── 실제 다운타임 = ④ ~ ⑦ ────────────┘
```

### 1.1 확정된 구성 (사용자 결정, 2026-10-01)

| 항목 | 결정 |
|---|---|
| 언어·빌드 | Java 17 이상, Spring Boot, Maven (DEC-01) |
| 실행 형태 | jar 하나에 엔진 + 내장 웹 화면 + CLI(Command Line Interface, 명령줄) 모드 (DEC-02) |
| 변경분 반영 | CDC(Change Data Capture, 변경 데이터 캡처), Debezium Embedded, Kafka 없이, 포크 없이 (DEC-03) |
| 전체 적재 | 자체 적재기, PG `COPY`, 병렬 (DEC-04) |
| 순서 | 워터마크(CDC LSN(Log Sequence Number, 로그 순번)) → 전체 적재 → 변경분 반복 반영 → 마지막 반영·검증·전환 (DEC-05) |
| 변환 규칙 | 설정 파일로 분리 (DEC-06) |
| 검증 | 건수·합계·해시 (DEC-07) |
| 상태 저장 | 대상 PG 관리 테이블, 재시작하면 이어서 (DEC-08) |
| 폐쇄망 | 외부 다운로드 0, CDN(Content Delivery Network, 외부 배포망) 없음, 나중에 jlink 설치본 (DEC-09) |

### 1.2 이 계획에서 정한 기본값 (PR #1 머지로 채택)

| 갈림길 | 기본값 |
|---|---|
| Java 버전 | Java 21 LTS(Long-Term Support, 장기 지원) 빌드·실행, 바이트코드 17 (DEC-10) |
| Spring Boot | 4.1.x, `kafka.version` 은 Debezium 기준 (DEC-11, §7 R3) |
| Debezium | 3.7.0.Final (DEC-12) |
| CDC 이벤트 처리 | 수집과 반영 분리: Debezium → `kdms.change_log` → 반영기 (DEC-13, §4.4) |
| Debezium 오프셋 저장 | `debezium-storage-jdbc` 로 대상 PG 에 (DEC-14) |
| 관리 테이블 생성 | 앱 시작 시 자체 DDL 스크립트, 버전 파일 추가 방식 (DEC-15) |
| 화면 | Thymeleaf + 직접 작성한 작은 JS·CSS (DEC-16) |
| CLI 해석 | picocli (DEC-17) |
| 시험용 원천 DB | KIS `MIG_MOCK` 의 사본 `KDMS_MOCK` (DEC-18) |
| 대상 PG DB | 새 DB `kdms`, 전용 역할 `kdms_app`(superuser 아님) (DEC-19) |

---

## 2. 시험 환경

```
Mac (개발, kdms.jar 실행)  ───── 같은 LAN ─────>  Windows 노트북 192.168.0.12
                                                   ├ SQL Server 2019 Developer :1433 (로그인 kodong_ms), SQL Agent
                                                   └ PostgreSQL 16 (Docker) :5432
```

- 앱은 비밀번호를 환경 변수(`KDMS_SRC_PASSWORD` 등)·`.env` 로 읽고, 설정 파일에는 `${…}` 자리표시만 쓴다. 비밀번호 규칙과 클라우드·Mac·노트북 분담은 [CLAUDE.md](../CLAUDE.md) §2·§3.
- 시험 환경 JDBC 는 `trustServerCertificate=true`, 운영 설정 예시는 인증서 검증을 켠 채로 (DEC-21, [issues.md](issues.md) E03).

### 2.1 0단계 준비물 (노트북, 사람이 한 번)

| 할 일 | 누가·어떻게 | 확인 |
|---|---|---|
| SQL Agent 시작 | 관리자 PowerShell `Start-Service SQLSERVERAGENT` | CDC 캡처 Job 이 돈다 |
| `KDMS_MOCK` 만들기 | Windows 인증으로 `MIG_MOCK` 백업 → `KDMS_MOCK` 으로 복원 (KDMS 저장소 `test/sql/mssql/00_restore_kdms_mock.sql`, 1단계에서 작성) | 테이블 수·건수가 `MIG_MOCK` 과 같다 |
| CDC 켜기 | Windows 인증(sysadmin)으로 `sys.sp_cdc_enable_db`, MVP 테이블마다 `sys.sp_cdc_enable_table` | `sys.databases.is_cdc_enabled = 1`, `cdc.change_tables` 에 테이블 수만큼 |
| 스냅숏 읽기 허용 | `ALTER DATABASE KDMS_MOCK SET ALLOW_SNAPSHOT_ISOLATION ON` | 전체 적재가 일관된 시점으로 읽는다(KIS:docs/zero-downtime.md §4-1) |
| `kodong_ms` 권한 | 대상 테이블 `SELECT`, `cdc` 스키마 `SELECT`(또는 게이팅 역할), `VIEW DATABASE STATE` 등. 정확한 최소 권한 목록은 1단계에서 Debezium 공식 문서 기준으로 확정해 스크립트로 만든다 | Debezium 이 오류 없이 스트리밍 시작 |
| PG DB·역할 | superuser 로 `CREATE ROLE kdms_app LOGIN …; CREATE DATABASE kdms OWNER kdms_app;` (스크립트 1단계에서 작성) | Mac 에서 `psql -h 192.168.0.12 -U kdms_app -d kdms` |
| LAN 접속 | `.env` `PG_BIND=0.0.0.0`, 방화벽 5432·1433 인바운드는 **개인 프로필·로컬 서브넷만**(KIS:docs/pg-lan-access.md) | Mac `nc -vz 192.168.0.12 1433`, `nc -vz 192.168.0.12 5432` |

---

## 3. MVP(Minimum Viable Product, 최소 기능 제품) 범위

**한 줄 정의**: `KDMS_MOCK` 의 테이블 몇 개를, 원천에 쓰기가 계속 들어오는 동안 전체 적재 + CDC 반영으로 따라가고, 쓰기를 멈춘 뒤 마지막 반영·검증까지 한 번에 끝낸다.

### 3.1 들어가는 것

| 영역 | 내용 |
|---|---|
| 대상 테이블 | `KDMS_MOCK` dbo 테이블 중 PK 가 있는 것 전부(KIS `sql/10_mssql/11_schema_pitfalls.sql` 정의, 8개 안팎. 실측 7개, DEC-43). KIS 함정이 모여 있다: CI(Case-Insensitive, 대소문자 무시) UNIQUE, char 끝 공백, nvarchar·varchar(CP949) 한글, money, bit, uniqueidentifier, rowversion, datetime·datetime2(7), IDENTITY, SEQUENCE 기본값, 계산 컬럼, 트리거, nvarchar(max), NUL 문자 |
| 스키마 변환 | 테이블·컬럼·PK·UNIQUE·NOT NULL·IDENTITY → 대상 DDL(Data Definition Language, 정의 언어) 생성. 변환 규칙 파일 적용. FK 는 생성만 하고 전환 직전에 켠다 |
| 전체 적재 | 워터마크 기록 후 SNAPSHOT 격리 수준으로 읽어 `COPY` 로 쓴다. 테이블 병렬 + 큰 테이블은 PK 구간 병렬 |
| CDC | Debezium Embedded, `snapshot.mode=no_data`(데이터 스냅숏은 우리 적재기가 하므로 스키마만). 변경 이벤트를 `kdms.change_log` 에 저장, 반영기가 LSN 순서로 멱등(idempotent, 여러 번 적용해도 결과가 같음) 적용 |
| 전환 | 쓰기 중지 확인 → 캡처 지연이 중지 시각을 넘었는지 확인 → 마지막 반영 0건 2회 → 검증 → `setval` → FK·트리거 켜기 |
| 검증 | 테이블별 건수·수치 합계·행 해시 합(KIS:docs/normalization.md 규칙 그대로), 불일치 시 PK 구간을 좁혀 행 단위 차이 목록 |
| 상태·재시작 | 작업·테이블·구간·워터마크·반영 위치를 `kdms` 스키마에. 어느 단계에서 죽어도 다시 실행하면 이어서 |
| 화면 | localhost 웹: 작업 목록, 테이블별 진행률, CDC 지연, 검증 결과, 시작·중지·전환 버튼 |
| CLI | 같은 기능을 명령으로(`kdms plan`, `kdms load`, `kdms sync`, `kdms cutover`, `kdms verify`, `kdms status`) |
| 폐쇄망 | 실행 중 외부 접속 0 을 시험으로 확인(네트워크를 끊고 실행) |

### 3.2 빠지는 것 (MVP 뒤)

- 뷰·SP·함수·트리거·SYNONYM·SQL Agent Job 의 자동 변환 → KIS:docs/appcompat.md 의 수작업 전환 규칙을 그대로 쓴다(KIS:docs/issues.md C01~C04). KDMS 는 목록과 경고만 낸다.
- 운영 중 원천 DDL 변경 추적(캡처 인스턴스 재생성). MVP 는 "적재·동기화 기간 스키마 동결"을 전제로, 바뀌면 감지해 멈춘다.
- PK 없는 테이블의 변경분 반영(전환 때 해당 테이블만 전체 재적재로 처리, §4.6).
- `MIG_SITE`(22개 테이블) 전체, 여러 DB 동시, RDS SQL Server 원천, 역방향 동기화(롤백), 인증·권한이 있는 원격 화면.
- jlink 설치본(7단계, MVP 직후).

---

## 4. 설계

### 4.1 모듈 (Maven 단일 모듈, 패키지로 나눔)

| 패키지 | 책임 |
|---|---|
| `kdms.config` | 설정 파일(YAML) 읽기·검사, `.env`/환경 변수 치환 |
| `kdms.catalog` | 원천 메타데이터 읽기(`sys.tables/columns/indexes/identity_columns/computed_columns/triggers/sequences`), 대상 메타데이터 |
| `kdms.rules` | 변환 규칙 엔진: 원천 컬럼 → 대상 타입·값 변환기 선택 |
| `kdms.ddl` | 대상 DDL 생성·적용(테이블, PK, UNIQUE, 시퀀스, 나중에 켤 FK) |
| `kdms.load` | 전체 적재기: 구간 분할, 원천 읽기, `CopyManager` 로 COPY, 구간 상태 기록 |
| `kdms.cdc` | Debezium 엔진 수명주기, 이벤트 → `change_log` 저장 |
| `kdms.apply` | `change_log` → 대상 테이블 멱등 적용, 반영 위치 기록 |
| `kdms.verify` | 건수·합계·해시 SQL 생성(원천 T-SQL / 대상 SQL), 비교, 행 단위 차이 |
| `kdms.cutover` | 전환 절차(상태 기계) |
| `kdms.state` | 관리 테이블 접근, 작업 상태 기계, 잠금(같은 작업 동시 실행 방지: `pg_advisory_lock`) |
| `kdms.web` | 내장 웹(Thymeleaf + 정적 JS·CSS), REST(Representational State Transfer, 상태 조회·조작 API) |
| `kdms.cli` | picocli 명령. `--no-web` 이면 웹 서버를 띄우지 않는다 |

웹 서버는 기본 `127.0.0.1` 에만 바인딩한다. LAN 에서 보려면 설정을 명시적으로 바꾼다.

### 4.2 관리 테이블 (대상 PG `kdms` 스키마)

테이블 정의·관계도·코드값·상태 전이·버전 이력은 [database.md](database.md).

### 4.3 전체 적재기

1. **워터마크 먼저**: Debezium 엔진을 `no_data` 모드로 띄워 스트리밍이 시작된 LSN 을 `watermark.start_lsn` 에 기록한다. 이 시점 이후 변경은 전부 `change_log` 로 들어온다.
2. 그다음 원천을 **SNAPSHOT 격리 수준**으로 읽는다(테이블·구간마다 별도 트랜잭션이라 시점은 구간마다 다르다. 워터마크가 모든 구간 시작보다 앞이므로 빠지는 변경은 없다. 겹치는 변경은 반영기가 멱등으로 다시 적용해 수렴한다).
3. 구간 분할: 단일 정수 PK 는 `MIN~MAX` 균등 분할, 그 밖의 PK 는 `NTILE` 로 경계값을 미리 뽑는다. 구간마다 `load_chunk` 행.
4. 쓰기: PgJDBC `CopyManager` 로 `COPY … FROM STDIN (FORMAT text)`. 탭·줄바꿈·역슬래시는 COPY 텍스트 규칙으로 이스케이프(KIS:docs/issues.md D02 의 bcp 문제를 피한다). 구간 재시도 방식은 구현에서 바꿨다: 구간 행과 DONE 표시를 한 트랜잭션에 커밋해 DELETE 없이 다시 넣는다 (DEC-28).
5. 대상 쪽 적재 중 설정: 인덱스(PK 제외)·FK 는 적재 뒤 생성, 트리거 없음, `synchronous_commit=off`(세션).
6. 병렬도: 설정값(기본 테이블 4 × 구간 2). 원천 부하를 보며 조정한다.

### 4.4 CDC 수집과 반영

```
Debezium Embedded (SQL Server 커넥터)
  └ 이벤트 배치 ──> kdms.change_log INSERT (한 트랜잭션) ──> 배치 커밋 후 오프셋 커밋(JdbcOffsetBackingStore)
                        ↑ 중복 수신(재시작 시) 은 UNIQUE 로 무시                 → 최소 1회 전달 + 멱등 = 결과 1회

반영기 (테이블별 전체 적재가 끝난 뒤부터)
  └ change_log 를 (commit_lsn, change_lsn, event_serial_no) 순서로 읽어
      c/u → INSERT … ON CONFLICT (pk) DO UPDATE (변경 후 값 전체)
      d   → DELETE WHERE pk = …
      PK 값이 바뀐 UPDATE → 삭제 + 입력 두 이벤트로 온다고 보고 처리(T-C07 로 확인)
    적용과 watermark.applied_lsn 갱신을 같은 트랜잭션으로
```

- `net_changes` 가 아니라 모든 변경을 순서대로 적용한다. 그래야 "적재 중 입력 후 삭제된 행"이 유령으로 남지 않는다(KIS:docs/zero-downtime.md §4-3).
- ~~대상 세션은 `session_replication_role = replica` 로 트리거·FK 를 끈 채 적용한다~~ → 대상 테이블에 트리거·FK 가 있으면 `kdms sync` 가 시작하지 않는다 (DEC-30, [cdc.md](cdc.md) §4)
- 원천 트리거가 쓴 행(예: 이력 테이블)도 CDC 로 그대로 들어오므로 대상 트리거는 전환 뒤에만 켠다.
- 계산 컬럼은 CDC 가 캡처하지 않는다(KIS:docs/zero-downtime.md §4-11) → 대상은 `GENERATED ALWAYS AS … STORED` 로 만들고 반영기는 그 컬럼을 쓰지 않는다. 식을 옮길 수 없는 컬럼은 변환 규칙에서 "값 컬럼 + 경고"로 명시해야 통과.
- 보존 기간: 수집이 계속 돌기 때문에 원천 CDC 보존(기본 3일)은 "수집이 멈춰 있는 최대 시간"만 넘지 않으면 된다. 수집이 멈춘 채 보존 기간이 지나면 Debezium 이 오류로 멈추게 두고(조용히 틀리지 않게), 화면에 "전체 적재부터 다시"를 띄운다.
- 지연 표시: ~~`현재 원천 max LSN 시각 − 마지막 반영 이벤트의 커밋 시각`(초)~~ → 5단계에서 정의를 바꿨다 (DEC-36, [cdc.md](cdc.md) §1).

### 4.5 전환 (컷오버)

KIS:docs/zero-downtime-rehearsal.md §4 를 자동화한다.

1. 사용자가 원천 쓰기를 멈추고 화면·CLI 에서 "전환 시작" (KDMS 는 앱 중지를 대신하지 않는다).
2. 원천 `sys.fn_cdc_map_lsn_to_time(sys.fn_cdc_get_max_lsn())` 이 전환 시작 시각보다 뒤가 될 때까지 기다린다(캡처 지연, §4-4).
3. 수집·반영이 **0건으로 2회 연속**이 될 때까지 반복. (구현은 2·3 을 drain 조건 하나로 한다: DEC-35, [cutover.md](cutover.md) §2)
4. 검증(§4.7). 불일치가 있으면 멈추고 목록을 보여 준다(전환하지 않음).
5. IDENTITY·SEQUENCE `setval`(원천 `IDENT_CURRENT`·`sys.sequences.current_value` 기준, KIS:docs/issues.md A09).
6. FK 켜기(`NOT VALID` 없이 검사), 보조 인덱스 확인, 대상 트리거는 사용자가 KIS appcompat 방식으로 배포(목록만 출력).
7. 소요 시간(2~6단계) = 예상 다운타임으로 기록.

### 4.6 PK 없는 테이블

CDC 반영을 멱등으로 할 수 없다(KIS:docs/zero-downtime.md §4-6). MVP 는 계획 단계에서 목록을 띄우고 기본 처리 = "전환 때 전체 재적재"(다운타임에 포함). 설정으로 제외할 수 있다.

### 4.7 검증

KIS:docs/normalization.md 를 **그대로** 코드로 옮긴다(규칙을 바꾸면 그 문서와 이 저장소 `docs/normalization.md` 를 함께 고친다).

- 건수: `COUNT_BIG(*)` ↔ `count(*)`.
- 합계: 정수·decimal·money 컬럼마다 `SUM(CAST(x AS decimal(38, 스케일)))` ↔ `sum(x)` (KIS A04 money 오버플로).
- 해시: 행 = 컬럼 정규화 문자열을 `|` 로 잇고 NULL 은 `\N`, **UTF-8 바이트**의 MD5(Message Digest 5) 앞 8바이트를 부호 있는 bigint 로 → 테이블 = 합. 원천은 `COLLATE Latin1_General_100_BIN2_UTF8` 로 UTF-8 바이트를 만들어 `HASHBYTES('MD5', …)`.
  - `ISNULL` 대신 `COALESCE`(KIS D01), datetime = 121 형식, datetime2 는 `datetime2(6)` 으로 맞춤(A06), char 는 RTRIM(A10), money 는 decimal(19,4) 경유, uniqueidentifier 소문자, 바이너리 16진 대문자.
- 원천·대상 계산은 각 DB 에서 하고 결과(숫자 몇 개)만 앱으로 가져와 비교한다. KIS 는 tds_fdw 로 PG 한 곳에서 비교했지만 KDMS 는 JDBC 두 개로 같은 일을 한다(대상 PG 에 확장 설치가 필요 없다).
- 불일치 시: 같은 해시를 PK 구간별로 다시 계산해 좁히고, 마지막 구간은 PK 별 해시를 비교해 `verify_row_diff` 에 남긴다. (구현은 구간 대신 PK 해시 묶음: DEC-29)
- 해시가 같아도 조회 결과가 다를 수 있는 항목(KIS B01~B14: 대소문자·끝 공백·정렬·LEN 등)은 **이관 오류가 아니라 설계 결정**이다. 검증 보고서에 "주의" 절로 따로 낸다(§5 규칙 파일의 결정값과 함께).

---

## 5. 변환 규칙 파일

규칙은 코드가 아니라 `kdms-rules.yml` 에 둔다. 기본 규칙 파일을 jar 에 넣고, 작업마다 덮어쓸 파일을 지정한다.
기본값은 KIS 에서 검증된 매핑(KIS:docs/normalization.md §1, datatype-pitfalls.md)이다. 아래는 형식 예시이며 키 이름은 1단계에서 확정한다.

```yaml
types:                       # 원천 타입 → 대상 타입
  money:        { to: "numeric(19,4)" }
  smallmoney:   { to: "numeric(10,4)" }
  bit:          { to: boolean }                    # 또는 smallint + CHECK (앱 SQL 의 "= 1" 이 많을 때)
  tinyint:      { to: smallint, check: "BETWEEN 0 AND 255" }
  uniqueidentifier: { to: uuid }                   # 정렬 순서가 달라진다(경고)
  rowversion:   { to: bytea }                      # 앱이 낙관적 잠금에 쓰면 별도 설계
  datetime:     { to: "timestamp(3)" }             # 3.33ms 값(.997 등) 그대로
  datetime2:    { to: "timestamp({min(p,6)})", round: half-up }   # 7자리 → 6자리 반올림(A06)
  datetimeoffset: { to: timestamptz, warn: "오프셋 버려짐" }
  nvarchar:     { to: "varchar({n})" }             # n = 문자 수. max → text
  varchar:      { to: "varchar({n})" }             # CP949 한글 → UTF-8 (바이트 증가, 문자 수는 같음)
  char:         { to: "char({n})" }
  text|ntext:   { to: text }
  image|varbinary|binary: { to: bytea }

identity:     { to: "generated by default as identity", setval: from_ident_current }   # A09
sequence:     { setval: from_current_value }

text:
  trailing_space: keep        # keep | rtrim  (B02·B04. 코드성 컬럼만 rtrim 하려면 columns 에서 개별 지정)
  nul_char: fail              # fail | strip | replace:"�"  (A02: 오류 없이 잘리는 것을 막는다. 건수 보고)
  case: keep                  # keep | upper | lower

collation:
  target: "C"                 # C | "ko-KR-x-icu" | ci(비결정 ICU, und-u-ks-level2)  (B01·B06·G08)
  ci_unique: lower_index      # none | lower_index | ci_collation  (B14: CI UNIQUE → UNIQUE (lower(col)))

sentinel_dates:               # A08: 1753-01-01, 1900-01-01, 9999-12-31
  action: keep                # keep | null | infinity

identifiers:
  case: lower                 # 대상 이름 소문자 스네이크, 63바이트 초과는 매핑표 필수
  map: {}                     # 원천이름: 대상이름

tables:                       # 테이블·컬럼 단위 덮어쓰기
  # dbo.some_table:
  #   columns:
  #     code_col: { trailing_space: rtrim, case: upper }
  #   exclude: false
```

- 규칙이 값을 바꾸면(rtrim·upper·센티널→NULL 등) **검증도 같은 규칙으로 원천 쪽을 정규화**한다. 바꾸지 않은 컬럼은 차이가 그대로 해시에 잡힌다(KIS normalization §0 "표현만 맞추고 값 차이는 숨기지 않는다").
- 계획 명령(`kdms plan`)이 테이블·컬럼별로 적용될 규칙, 경고(바이트 증가, 63바이트 초과 이름, PK 없음, 계산 컬럼, 트리거, LOB, NUL 포함 여부)를 보고서로 낸다. 사람이 보고 규칙 파일을 고친 뒤 실행한다.
- 전체 적재와 CDC 는 값이 들어오는 길이 다르다(JDBC ResultSet ↔ Debezium 변환값). **두 길이 같은 정규 값으로 모인 뒤 같은 변환기를 지나게** 만든다. Debezium 설정은 `decimal.handling.mode=precise`, `binary.handling.mode=bytes`, `time.precision.mode=adaptive` 에서 시작하고, 1단계 시험으로 확정한다(uniqueidentifier 는 대문자 문자열로 오므로 소문자로).

---

## 6. 단계별 계획

단계를 나누고 머지하는 규칙(단계 = 브랜치 + draft PR, 완료 기준은 Mac 의 `runs/` 파일, 머지는 사용자)은 [CLAUDE.md](../CLAUDE.md) §4.
완료 근거의 시각은 WORKLOG 항목 시각(KST, DEC-45. 클라우드 runs 파일 이름은 UTC), 단계 요약은 [WORKLOG.md](../WORKLOG.md) 의 "단계 요약" 항목.

| 단계 | 내용 | 완료 기준 | 상태 | 완료 근거(WORKLOG 시각·runs 파일·PR 번호) |
|---|---|---|---|---|
| **0. 환경 준비** | §2.1. 노트북에서 사람이 실행할 SQL·명령을 1단계 PR 에 함께 넣는다 | Mac 에서 1433·5432 접속, `KDMS_MOCK` CDC 켜짐, `kdms_app` 로 로그인 | 완료 | 0단계만의 기록은 없다. 간접 근거: WORKLOG 2026-10-08 11:00(Mac → 노트북 1433·5432, `kdms_app`, `runs/20261008_1056_p3_*.txt`), 2026-10-08 12:26(CDC 7개 테이블 스트리밍, `runs/20261008_1226_p4_sync1.txt`). 준비 SQL 은 PR #2·#3 |
| **1. 골격** | Maven 프로젝트, Spring Boot 4.1, 패키지 구조, 설정 파일·`.env.example`·`.gitignore`, 관리 테이블 DDL, CLI 뼈대(`kdms status`), 웹 첫 화면, 라이선스 보고서 자동 생성(`license-maven-plugin`, SBOM(Software Bill of Materials, 구성 요소 명세) `cyclonedx-maven-plugin`), 의존성 버전 고정(Kafka 버전 정렬), 시험 준비 SQL | `mvn -o package`(오프라인) 성공, 네트워크를 끊고 `java -jar kdms.jar status` 가 두 DB 버전을 출력, 라이선스 보고서에 미확인 라이선스 0 | 완료 | PR #2·#3·#5 머지(2026-10-01). WORKLOG 2026-10-01 1단계(클라우드: 단위 시험 31, 오프라인 빌드, 라이선스 검사). runs 파일 없음. Mac 에서 네트워크를 끊고 `status` 를 실행한 기록은 없다(6단계 T-N01 로 남음) |
| **2. 스키마 변환** | 원천 카탈로그 읽기, 규칙 엔진, DDL 생성·적용, `kdms plan` 보고서 (구현: [schema-conversion.md](schema-conversion.md)) | `KDMS_MOCK` 의 대상 DDL 이 KIS `sql/20_pg/gen/mock.sql` 의 테이블 정의와 같은 타입(차이는 규칙 파일 결정으로 설명) | 완료 | PR #4 머지(2026-10-06). WORKLOG 2026-10-01 2단계(클라우드). Mac 2026-10-06 `SourceCatalogIT`·`TargetDdlIT` 5개 통과(PR #4 설명, runs 파일 없음). 노트북 PG 적용 `runs/20261008_1056_p3_schema.txt` |
| **3. 전체 적재 + 검증** | 구간 분할, COPY 적재, 재시작, 건수·합계·해시 검증, 행 단위 차이 (구현: [load-verify.md](load-verify.md), [normalization.md](normalization.md)) | 쓰기 없는 상태에서 MVP 테이블 전부 검증 일치(KIS 76/76 처럼 항목 수로 보고). 적재 도중 프로세스를 죽였다 다시 실행해 이어서 끝나고 검증 일치 | 완료 | PR #8 머지(2026-10-08). WORKLOG 2026-10-06 23:40(클라우드 30/30, `runs/20261006_1440_p3_cloud_load_verify.txt`), 2026-10-08 11:00(Mac→노트북 48,053행 30/30, kill -9 뒤 30/30, `runs/20261008_1056_p3_{build,schema,load,verify,kill,integration}.txt`) |
| **4. CDC 수집·반영** | Debezium Embedded, `change_log`, 반영기, 워터마크, 지연 표시 (구현: [cdc.md](cdc.md)) | 쓰기 부하(KIS `sql/50_cdc/11_mssql_writes.sql` 와 같은 방식의 KDMS 시험 스크립트)를 넣는 동안 적재 → 반영, 쓰기 중지 후 검증 일치 | 완료 | PR #9 머지(2026-10-08). WORKLOG 2026-10-08 11:57(클라우드 30/30, `runs/20261008_0257_p4_cloud_e2e.txt` 외 3개), 2026-10-08 12:26(Mac→노트북 30/30, `runs/20261008_1226_p4_*.txt` 9개) |
| **5. 전환 + 화면·CLI 마무리** | 전환 상태 기계, `setval`, FK, 다운타임 측정, 웹 화면(진행률·지연·검증), CLI 전 명령 | 시나리오 S1~S4(정의는 [cutover.md](cutover.md) §6, DEC-38) 통과, 전환 소요 시간 보고 | 완료 | PR #10 머지(2026-10-08). WORKLOG 2026-10-08 13:10(클라우드 S1~S4·웹, `runs/20261008_0410_p5_cloud_scenarios.txt`·`_0424_p5_cloud_s4.txt`·`_0425_p5_cloud_web.txt`), 2026-10-08 15:44(Mac→노트북 S1 13.0초·30/30·S4, `runs/20261008_1547_p5_*.txt`·`runs/20261008_1603_p5_s4.txt`). S2·S3 은 클라우드로 갈음 |
| **6. MVP 리허설** | §8 전체를 처음부터 2회. 문서(운영 절차서) | 두 번 모두 합격, 절차서만 보고 다시 할 수 있음 | 남음 | PR #11 draft(머지 전) |
| 7. 설치본 | jlink 로 JRE 포함 압축본(Windows·Linux), 시작 스크립트 | 자바가 없는 PC 에서 실행 | 남음 | |
| 8. 확장 | `KDMS_SITE`(22테이블), PK 없는 테이블, DDL 변경 감지 후 캡처 인스턴스 교체, 대용량 성능 | 별도 계획 | 남음 | |

---

## 7. 위험과 대응

| ID | 위험 | 대응 |
|---|---|---|
| R1 | Debezium 이 요구하는 원천 권한이 금융권 DBA 승인 범위를 넘을 수 있다 | 1단계에서 최소 권한 목록을 문서화. CDC 켜기는 DBA 가 하고 KDMS 로그인은 읽기만. 4단계 실측으로 확인한 목록: [cdc.md](cdc.md) §7 |
| R2 | CDC 캡처가 멈추면 원천 로그가 잘리지 않아 디스크가 찬다(`log_reuse_wait_desc = REPLICATION`) | 화면에 원천 로그 사용률·`log_reuse_wait_desc`·캡처 지연 표시, 임계값 경고 |
| R3 | Spring Boot 와 Debezium 이 서로 다른 Kafka·Jackson 버전을 끌어온다. Kafka Connect 런타임이 Jetty·Jersey 를 함께 끌어온다 | `kafka.version` 을 Debezium 기준으로 고정, `mvn dependency:tree` 를 1단계 결과에 남김. 쓰지 않는 Jetty·Jersey 는 제외를 시도하고 엔진이 뜨는지 시험 |
| R4 | 전체 적재 경로와 CDC 경로의 값 표현 차이(datetime 시간대, datetime2 나노초, uniqueidentifier 대소문자, money 스케일) | 같은 행을 두 경로로 넣어 해시가 같은지 확인하는 시험(T-C05) |
| R5 | LOB 컬럼(nvarchar(max) 등)이 UPDATE 에서 바뀌지 않았을 때 CDC 이벤트 값이 어떻게 오는지 미확인 | 4단계 실측: 값 대신 `__debezium_unavailable_value` 가 온다. 그 컬럼만 빼고 UPDATE 한다(원천 재조회 불필요, [cdc.md](cdc.md) §2) |
| R6 | 원천 DDL 변경(컬럼 추가)이 동기화 중에 일어남 | MVP 는 스키마 동결 전제. 감지하면 반영을 멈추고 알림 |
| R7 | `change_log` 가 크게 늘어난다 | 반영 끝난 행은 반영 트랜잭션에서 바로 삭제(4단계), 반영 대기 건수 표시 |
| R8 | 이관 중 로그·임시 데이터에 개인정보 평문 | 로그에 행 값을 쓰지 않는다(PK 만, 설정으로도 못 켜게). `change_log` 는 반영 후 삭제 |
| R9 | 시험 환경 1대(노트북)라 부하·시간 수치가 운영과 다르다 | 수치는 "건수 대비 비율"로만 보고. KIS 처럼 AC 전원·절전 해제에서 측정 |

---

## 8. 시험 계획 (KIS 함정 → KDMS 시험)

### 8.1 변환·적재 (3단계까지)

| 시험 | 근거(KIS) | 기대 |
|---|---|---|
| T-L01 datetime `.997`, `.007` 등 3.33ms 값 | issues A05, normalization §1 | 해시 일치 |
| T-L02 datetime2(7) 7자리 → 6자리 반올림 | A06 | 원천도 `datetime2(6)` 으로 맞춘 해시 일치 |
| T-L03 money 극값 합계 | A04 | 합계 비교에서 오버플로 없음 |
| T-L04 bit → boolean | A07 | 해시 일치 |
| T-L05 IDENTITY 현재값 > MAX(id) | A09 | 전환 후 대상 다음 값 = 원천 `IDENT_CURRENT + 1` |
| T-L06 SEQUENCE 기본값·현재값 | B09, B13 | `DEFAULT nextval`, `setval` 일치 |
| T-L07 char 끝 공백 | A10 | char 는 RTRIM 정규화로 일치 |
| T-L08 varchar 끝 공백 | B02·B04 | `trailing_space: keep` 이면 값 보존·해시 일치, 보고서 "주의" 에 표시 |
| T-L09 NUL 문자 | A02 | `nul_char: fail` 이면 적재 전에 건수와 함께 멈춤. `strip` 이면 원천 정규화에도 같은 규칙 → 일치 |
| T-L10 CP949 varchar 한글·`?` | A03 | 한글 값 보존. `?` 는 원천 손실이라 건수만 보고 |
| T-L11 nvarchar 한글 바이트 증가 | datatype-pitfalls §1 | 문자 수 그대로, 인덱스 키 2,700바이트 근접 컬럼 경고 |
| T-L12 CI UNIQUE (`Kim01`/`kim01`) | B14 | `ci_unique: lower_index` 면 대상도 `kim01` 입력 거부 |
| T-L13 계산 컬럼 | B12 | `GENERATED … STORED` 로 생성, 값 일치 |
| T-L14 센티널 날짜 | A08 | 규칙대로(기본 keep) |
| T-L15 탭·CR·LF·역슬래시가 든 값 | D02 | COPY 이스케이프로 해시 일치 |
| T-L16 적재 중 강제 종료 후 재실행 | — | 끝난 구간은 건너뛰고 나머지만, 최종 검증 일치 |
| T-L17 검증 SQL 이 `ISNULL` 을 쓰지 않음 | D01 | 생성된 원천 SQL 에 `ISNULL` 없음(단위 테스트) |

### 8.2 CDC·전환 (4~5단계)

| 시험 | 근거(KIS) | 기대 |
|---|---|---|
| T-C01 쓰기 중 전체 적재 + 반영 → 쓰기 중지 → 검증 | zero-downtime-rehearsal §3~4 (cdc 방식 합격 기준) | missing 0 · extra 0 · diff 0 |
| T-C02 워터마크를 적재 **뒤**에 기록하도록 바꾼 실험 빌드 | rehearsal §6 | 차이가 생겨야 정상(검증이 잡아내는지 확인) |
| T-C03 적재 중 입력 후 삭제된 행 | zero-downtime §4-3 | 유령 행 없음 |
| T-C04 원천 트리거가 쓴 이력 행 | issues G13 | 대상 이력 행 수 = 원천(중복 없음) |
| T-C05 같은 행이 적재 경로와 CDC 경로로 들어왔을 때 값 동일 | §7 R4 | 행 해시 일치 |
| T-C06 LOB 컬럼 미변경 UPDATE | §7 R5 | 대상 LOB 값 유지 |
| T-C07 PK 값을 바꾸는 UPDATE | — | 옛 PK 삭제, 새 PK 입력 |
| T-C08 KDMS 프로세스 강제 종료 후 재시작(수집 중·반영 중 각각) | — | 중복·누락 없이 이어감, 검증 일치 |
| T-C09 SQL Agent 1분 중지 후 재시작 | rehearsal §6 | 지연이 늘었다가 따라잡음, 화면에 `REPLICATION` 대기 표시 |
| T-C10 보존 기간 초과 | rehearsal §6 | 오류로 멈추고 "전체 적재부터 다시" 안내(조용히 틀리지 않음) |
| T-C11 전환: 캡처 지연 확인 → 0건 2회 → 검증 → setval | zero-downtime §4-4, A09 | 소요 시간 기록, 전환 후 대상에 신규 입력 시 PK 충돌 없음 |
| T-C12 긴 트랜잭션이 늦게 커밋 | rehearsal `tgt_rvmax` | CDC(LSN 순)는 누락 없음 |

### 8.3 조회 동작 차이 보고 (값은 같지만 결과가 다른 곳)

KIS `sql/30_verify/90_behavior_diff.sql` 의 B01~B14 를 KDMS 검증 보고서의 "주의" 절로 옮긴다. 이 항목은 합격·불합격이 아니라 **규칙 파일의 어떤 결정 때문에 결과가 이렇게 나오는지**를 보여 주는 것이 목적이다.

### 8.4 폐쇄망

| 시험 | 기대 |
|---|---|
| T-N01 네트워크 차단 상태에서 jar 실행·적재·CDC·검증 | 정상 동작 |
| T-N02 jar 안 정적 파일 검사 | `http://`·`https://` 외부 URL 을 참조하는 `<script>`·`<link>` 0 |
| T-N03 실행 중 외부 연결 감시(macOS `lsof -i`) | 설정한 두 DB 외 연결 0 |

---

## 9. 저장소 구조 (1단계에서 만들 것)

초안 1 당시의 계획이다. 지금 구조는 [../README.md](../README.md) 의 "구조" 절(기본 규칙 파일은 `config/` 가 아니라 `src/main/resources/kdms-rules.yml`, 관리 테이블은 버전마다 파일 하나).

```
KDMS/
├ pom.xml
├ .env.example            # KDMS_SRC_* / KDMS_TGT_* (비밀번호 빈 값)
├ .gitignore              # .env, runs 의 접속정보, target/
├ config/
│  ├ kdms.example.yml     # 접속(환경 변수 자리표시), 병렬도, 대상 테이블 목록
│  └ kdms-rules.yml       # §5 기본 규칙
├ src/main/java/kdms/…    # §4.1
├ src/main/resources/
│  ├ db/kdms-schema.sql   # 관리 테이블
│  ├ templates/, static/  # 화면(외부 CDN 없음)
├ src/test/java/…         # 단위 테스트(DB 없음) + 통합 테스트(태그 integration, Mac 에서)
├ test/sql/mssql/         # KDMS_MOCK 복원·CDC 켜기·쓰기 부하
├ test/sql/pg/            # kdms DB·역할 생성
├ docs/
│  ├ plan.md, licenses.md
│  └ normalization.md     # KIS 규칙을 옮기고 KDMS 구현 위치를 적은 것(3단계)
├ runs/                   # Mac 실행 결과 원문
└ WORKLOG.md              # 실행 기록(KIS 형식)
```

---

## 10. 승인 때 정할 것

1. §1.2 기본값(특히 Java 21 빌드·17 바이트코드, Spring Boot 4.1, 시험 원천을 `KDMS_MOCK` 사본으로 두는 것). → DEC-10~DEC-19 (PR #1 머지로 채택, 그대로 구현됨. 사용자가 항목별로 승인한 기록은 없다)
2. MVP 테이블 범위(§3.1: `KDMS_MOCK` 의 PK 있는 테이블 전부). → DEC-43 (실측 7개 테이블)
3. 0단계 노트북 작업(복원·CDC 켜기·PG 역할 생성)을 사용자가 직접 할지, Remote Control 로 노트북 세션에 맡길지. → DEC-44 (사용자가 직접)
