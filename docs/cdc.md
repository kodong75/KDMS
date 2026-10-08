# 변경분 수집·반영 (4단계, CDC)

[plan.md](plan.md) §6 4단계: Debezium Embedded, `change_log`, 반영기, 워터마크, 지연 표시.
CDC(Change Data Capture, 변경 데이터 캡처)는 원천 MS-SQL 의 CDC 기능(캡처 Job 이 트랜잭션 로그를 읽어 `cdc.*_CT` 표에 쌓는다)을 Debezium SQL Server 커넥터가 읽는 방식이다. Kafka 브로커는 쓰지 않는다.
전체 적재·검증은 [load-verify.md](load-verify.md), 값 정규화는 [normalization.md](normalization.md).

## 1. 순서와 명령

```
터미널 A: kdms sync            ─ "워터마크 기록: …" 이 나올 때까지 기다린다. 계속 띄워 둔다
터미널 B: kdms load            ─ 원천 쓰기가 있는 동안 전체 적재. 끝난 테이블부터 A 가 변경을 반영한다
            … 원천 쓰기를 멈춘다(전환 직전) …
터미널 A: Ctrl+C               ─ 진행 중 배치를 마치고 멈춘다
          kdms sync --drain    ─ 마지막 변경까지 반영하고 끝난다(종료 코드 0)
          kdms verify          ─ 건수·합계·해시. 30/30 이 기준(KDMS_MOCK)
          kdms schema --phase post-load   ─ UNIQUE·인덱스(반영 중에는 만들지 않는다, §4)
```

| 명령 | 하는 일 | 바꾸는 것 |
|---|---|---|
| `kdms sync` | Debezium 엔진을 띄워 변경을 `kdms.change_log` 에 모으고, 적재가 끝난(LOADED) 테이블의 변경을 대상에 반영. `sync.status_seconds` 마다 진행 줄을 찍는다 | `kdms.watermark·change_log·job_table(반영 위치)·event_log·debezium_offset_<job>·debezium_schema_history_<job>`, 대상 테이블 |
| `kdms sync --drain` | 위와 같고, 원천 쓰기가 멈춘 뒤 마지막 변경까지 반영했으면 끝낸다(§5) | 위와 같음 |
| `kdms load` | 워터마크가 있어야 적재한다. 없으면 거부(종료 코드 4) | 3단계와 같음 |
| `kdms load --no-cdc` | 워터마크 없이 적재(원천 쓰기가 없는 시험만. 3단계 방식) | |
| `kdms reset --yes` | 대상 테이블을 비우고 적재 구간·워터마크·`change_log`·Debezium 오프셋을 지워 작업을 SCHEMA_DONE 으로 되돌린다. 테이블 정의는 남긴다. `kdms sync`·`load` 가 돌고 있으면 거부 | 대상 테이블, `kdms` 관리 테이블 |

`kdms sync` 종료 코드: 0 정상 중지·따라잡음, 1 설정 오류, 2 접속 실패, 3 계획에 오류, 4 시작 거부(아래 표), **5 수집·반영 실패**(원천 DDL 변경, 보존 기간 초과, 반영 오류).
`kdms reset`: 0 완료, 4 거부(`--yes` 없음, 실행 중인 sync·load, 작업 없음).

진행 줄 예(클라우드 실측):

```
[02:45:11] 수집 7,104 · 반영 7,104 · 대기 0 · 지연 0.0초 · 원천 마지막 변경 02:45:06
[02:44:21] 수집 4,835 · 반영 0 · 대기 4,835(적재 전 테이블 7개, 그 변경 4,835) · 지연 586.0초 · 원천 마지막 변경 02:43:20
```

- **수집**: 이 작업이 `change_log` 에 넣은 변경 수(누적), **반영**: 대상에 적용한 수(누적), **대기**: 아직 반영하지 않은 수(그중 적재가 안 끝난 테이블의 것은 괄호).
- **지연**: 원천의 마지막 커밋 시각 − 대상에 반영한 마지막 변경의 원천 커밋 시각(둘 다 원천 시계, `cdc.lsn_time_mapping`). 반영 대기가 0 이고 Debezium 이 원천 마지막 커밋 LSN 까지 읽었으면 0. 적재 전에는 워터마크 시각 기준이라 크게 나온다.
- 캡처 Job 이 멈추면(SQL Agent 중지 등) `lsn_time_mapping` 도 멈춰 위 지연이 0 으로 보인다. 그래서 캡처 Job 의 마지막 로그 훑기(`sys.dm_cdc_log_scan_sessions`, 빈 훑기 포함)가 15초보다 오래됐으면 그 시간을 지연으로 쓰고 `원천 캡처 Job 이 N초째 로그를 읽지 않음(SQL Agent 확인)` 을 붙인다. 원천 DB 의 `log_reuse_wait_desc` 가 `REPLICATION` 이면 `원천 로그 REPLICATION 대기` 도 붙는다(plan.md R2, T-C09).
- 같은 값이 `kdms.watermark`(`stream_lsn·src_max_lsn·src_max_lsn_at·pending_changes·lag_seconds·sync_status_at`)에 남는다. 웹 화면 표시는 6단계.

설정(`config/kdms.yml` 의 `sync:`): `batch_size`(반영 트랜잭션 하나의 변경 수, 기본 1000), `poll_ms`(반영할 것이 없을 때 쉬는 시간, 기본 500), `status_seconds`(기본 10).

### 1.1 시작 거부(종료 코드 4)

| 메시지 | 이유·할 일 |
|---|---|
| 작업 … 이 없다 | `kdms schema` 먼저 |
| … 단계다 | SCHEMA_DONE·LOADING·SYNCING·FAILED 에서만 |
| 원천 CDC 캡처 인스턴스가 없는 테이블 | DBA 가 `sys.sp_cdc_enable_table`(`test/sql/mssql/10_enable_cdc.sql`). 로그인에 `cdc` 스키마 읽기·게이팅 역할이 없어도 이렇게 보인다(`20_grant_kdms_login.sql`) |
| 대상 테이블에 트리거·FK 가 있어 | 반영 중 트리거가 돌면 이력이 두 번 생기고(KIS issues G13), FK 는 테이블끼리 순서를 맞추지 않으므로 막는다. 트리거는 끄고 FK 는 전환 때 만든다(`kdms schema` 는 원래 둘 다 만들지 않는다) |
| 워터마크는 있는데 Debezium 오프셋이 비어 있다 | 이어 받을 위치를 몰라 변경이 빠질 수 있다. `kdms reset --yes` 뒤 처음부터 |
| 다른 kdms sync 가 … 동기화하고 있다 | 작업마다 하나만(PG advisory lock `kdms.sync:<작업>`) |

## 2. 워터마크와 적재가 맞물리는 방식 (빠짐·중복 없음의 근거)

1. `kdms sync` 가 Debezium 을 `snapshot.mode=no_data` 로 띄운다. 엔진은 원천의 현재 최대 LSN 에서 스트리밍을 시작하고, 그 뒤 커밋된 변경을 모두 보낸다.
2. 스트리밍 시작을 알리는 첫 레코드(하트비트 또는 변경)를 받은 배치에서 그 위치를 `kdms.watermark.start_lsn` 으로 **`change_log` 저장과 같은 트랜잭션에** 기록한다. 그 뒤에 Debezium 오프셋을 커밋한다.
3. `kdms load` 는 워터마크가 있어야 시작한다. 그래서 모든 적재 구간(SNAPSHOT 격리, 구간마다 시점이 다르다)은 워터마크 **뒤** 시점을 읽는다. 워터마크와 구간 시점 사이의 변경은 `change_log` 에 있다.
4. 반영기는 적재가 끝난(`job_table.status = 'LOADED'`) 테이블의 변경만, `(commit_lsn, change_lsn, event_serial_no)` 순서로, **멱등**하게 적용한다. 적재가 이미 담은 변경을 다시 적용해도 결과가 같다.

| 변경 | 대상에 하는 일 |
|---|---|
| c(입력)·u(수정) | `INSERT … ON CONFLICT (pk) DO UPDATE SET 나머지 = EXCLUDED.…` (변경 후 값 전체). 값은 문자열 매개변수 + `CAST(? AS 대상 타입)` |
| u 인데 PK 가 바뀜 | Debezium 은 d + c 두 이벤트로 보낸다(같은 commit/change LSN, event_serial_no 1·2, 실측). 그대로 삭제·입력 |
| u 인데 LOB 컬럼이 바뀌지 않음 | Debezium 은 그 컬럼 값을 주지 않는다(`__debezium_unavailable_value`, 실측). 그 컬럼만 뺀 `UPDATE … WHERE pk`. 0행이면(대상에 행이 없는데 LOB 값을 모름) 반영 실패로 멈춘다 |
| d(삭제) | `DELETE … WHERE pk = 변경 전 PK`. 없으면 0행(이미 없음) |

- 값 변환은 전체 적재와 **같은 함수**(`CopyValues.value`)를 지난다. Debezium 값 → 정규 값(`CdcValues.decode`) → 규칙(끝 공백·NUL·센티널·반올림·uuid 소문자·bytea) → 대상 입력 문자열. 그래서 같은 행이 적재 경로와 CDC 경로로 들어와도 해시가 같다(T-C05).
- 원천 트리거가 쓴 이력 행(rating → rating_hist)은 CDC 로 따로 들어온다. 대상에는 트리거가 없으므로 한 번만 생긴다(T-C04).
- 계산 컬럼은 이벤트에 있어도 쓰지 않는다(대상은 `GENERATED … STORED`).
- `net_changes` 가 아니라 모든 변경을 순서대로 적용하므로 "적재 중 입력 직후 삭제된 행" 이 유령으로 남지 않는다(T-C03).

## 3. 상태와 재시작

```
Debezium 배치 ──> change_log INSERT + 워터마크(처음 한 번) + changes_captured  ── 한 트랜잭션 ──> 그 뒤 Debezium 오프셋 커밋
반영기 ──> change_log 에서 배치를 읽어 대상 적용 + 적용한 change_log 행 삭제 + 테이블별·작업별 반영 위치 갱신 ── 한 트랜잭션
```

| 표(`kdms` 스키마) | 내용 |
|---|---|
| `watermark` | `start_lsn`(워터마크), 반영한 마지막 위치·시각, 수집·반영 누계, 상태 줄 값 |
| `change_log` | 반영 전 변경. `payload` = `{"before":{…},"after":{…}}` 원천 값(문자열). **반영하면 바로 지운다**(행 값을 남기지 않는다, R8). NUL 이 든 문자열은 jsonb 가 못 담아 `{"b64":…}` |
| `job_table.applied_*` | 테이블별 마지막 반영 위치·반영 수 |
| `debezium_offset_<job_id>`, `debezium_schema_history_<job_id>` | Debezium 이 쓰는 표. `JdbcOffsetBackingStore` 는 저장할 때마다 표 전체를 지우고 다시 쓰므로 작업마다 표를 나눈다 |

- **어디서 죽어도**(수집 중·반영 중, `kill -9`) 다시 `kdms sync` 하면 커밋된 오프셋 다음부터 받는다. 오프셋 커밋 전에 죽으면 Debezium 이 그 배치를 다시 보내는데, (1) 이미 `change_log` 에 있으면 UNIQUE`(job_id, commit_lsn, change_lsn, event_serial_no)` 로, (2) 이미 반영하고 지웠으면 "테이블 반영 위치 이하" 조건으로 버린다. 결과는 한 번 적용과 같다(T-C08).
- 반영기는 반영 대상 테이블의 `job_table` 행을 `FOR SHARE` 로 잡는다. `kdms load --reset`(그 행을 PENDING 으로 바꿈)과 겹치지 않는다. 다시 적재하는 테이블의 변경은 다시 LOADED 가 될 때까지 쌓아 둔다.
- 반영 세션은 `synchronous_commit = off`. 커밋이 몇 ms 늦게 디스크에 가도 PG 가 죽으면 그 트랜잭션 전체가 없어질 뿐이고, 지운 `change_log` 도 함께 되살아나 다시 반영한다.

## 4. 반영 중 대상 DDL

- **UNIQUE·보조 인덱스**: `kdms load` 는 워터마크가 있으면(CDC 모드) 모두 적재돼도 적재 뒤 DDL 을 적용하지 않는다. 변경을 순서대로 다시 적용하는 동안 잠깐 UNIQUE 가 겹칠 수 있기 때문이다(예: 원천에서 login_id 를 A→B, C→A 로 바꾼 순서를 따라갈 때). 쓰기를 멈추고 `--drain` 뒤 `kdms schema --phase post-load`. 5단계 전환 명령이 이 순서를 자동으로 한다.
- **트리거·FK**: §1.1 처럼 있으면 시작하지 않는다. plan.md §4.4 는 `session_replication_role = replica` 로 끄는 방식을 적었지만, 그 설정은 PG superuser 만 바꿀 수 있어(이관 계정 `kdms_app` 은 superuser 가 아니다) "없을 때만 반영" 으로 바꿨다.

## 5. `--drain` 이 끝나는 조건

캡처 Job 은 기본 5초마다 로그를 훑고, `sys.fn_cdc_get_max_lsn()` 은 쓰기가 없으면 움직이지 않는다(실측). 그래서 "원천 최대 LSN 까지 반영" 만으로는 캡처되지 않은 커밋을 놓칠 수 있다.

1. 모든 테이블이 LOADED 이고, 반영 대기 0, Debezium 이 읽은 위치 ≥ 원천 마지막 커밋 LSN(`cdc.lsn_time_mapping`, `tran_id <> 0x00`)이면 그 순간의 원천 시각을 표시(mark)로 잡는다.
2. 캡처 Job 이 mark **뒤에 시작한** 로그 훑기를 끝냈고(`sys.dm_cdc_log_scan_sessions`, 빈 훑기는 `empty_scan_count`·`end_time`) 그 뒤에도 mark 이후 커밋이 없으며 여전히 1 이 성립하면 끝낸다.
3. mark 뒤 커밋이 보이면 "원천 쓰기가 아직 있다" 를 한 번 찍고 mark 를 다시 잡는다. 쓰기가 멈출 때까지 끝나지 않는다(클라우드 실측: 쓰기 중에 시작한 drain 이 쓰기 끝 7초 뒤 끝남, 검증 30/30).

보통 5~10초 걸린다. 원천 쓰기가 아주 드물면(몇 분에 한 건) 그 사이에 끝날 수 있으므로, 앱 쓰기를 멈춘 다음에 쓴다(전환 절차, plan.md §4.5).

## 6. Debezium 설정과 확인한 것

`kdms.cdc.DebeziumProps`(비밀번호는 로그·화면에 안 나온다):

| 설정 | 값 | 이유 |
|---|---|---|
| `snapshot.mode` | `no_data` | 데이터 스냅숏은 KDMS 적재기가 한다. 스키마만 읽는다 |
| `table.include.list` | 계획 테이블 중 PK 있는 것 | PK 없는 테이블은 반영하지 않는다(plan.md §4.6, 전환 때 재적재) |
| `decimal.handling.mode`·`binary.handling.mode`·`time.precision.mode` | `precise`·`bytes`·`adaptive` | 값 손실 없이 받기(money 는 scale 4 BigDecimal, datetime 은 ms epoch 로 .997 유지, datetime2(7) 은 ns) |
| `heartbeat.interval.ms` | 1000 | 쓰기가 없어도 처리 위치가 움직여 워터마크·지연을 잡는다 |
| `offset.storage` | `kdms-jdbc`(→ `KdmsOffsetStore`) | 아래 |
| `offset.flush.interval.ms` | 0 | 배치마다 오프셋 커밋(`change_log` 커밋 뒤) |
| `tombstones.on.delete` | false | 삭제 뒤 빈 레코드 없음 |

**오프셋 저장소 우회(KdmsOffsetStore)**: `debezium-storage-jdbc` 3.7.0 은 저장한 오프셋 JSON 을 Jackson 으로 읽어 작은 정수를 `Integer` 로 돌려주는데, SQL Server 커넥터는 `event_serial_no` 를 `Long` 으로 꺼낸다. 그래서 **오프셋이 저장된 뒤 다시 시작하면** `ClassCastException: Integer cannot be cast to Long` 으로 멈춘다(2026-10-08 클라우드 실측, 첫 시작은 오프셋이 없어 문제 없음). Debezium 을 고치지 않고(포크 금지) 공개 확장점만 써서, `JdbcOffsetBackingStore` 를 상속해 읽은 값의 `Integer` 를 `Long` 으로 바꾸는 `kdms.cdc.KdmsOffsetStore` 를 `META-INF/services/io.debezium.spi.storage.OffsetStoreProvider` 로 등록했다. 같은 이유로 `debezium-storage-jdbc` 가 provided 로만 선언한 `debezium-storage-common`(Apache-2.0)을 의존성에 넣었다(없으면 `NoClassDefFoundError: DefaultOffsetStorageReader`). Debezium 을 올릴 때 이 둘이 아직 필요한지 다시 본다.

실측한 이벤트 모양(클라우드, SQL Server 2019 CU32, Debezium 3.7.0):

| 경우 | 이벤트 |
|---|---|
| LOB(nvarchar(max)) 미변경 UPDATE | `before.body`·`after.body` = `__debezium_unavailable_value` → 위 §2 표 |
| PK 바꾸는 UPDATE | d + c, 같은 LSN, event_serial_no 1·2 |
| 값을 안 바꾸는 UPDATE(`SET x = x`) | 캡처되지 않음(원천 CDC 가 남기지 않는다) |
| 원천 DDL(스트리밍 중 스키마 변경 레코드) | **반영을 멈추고 오류**(종료 코드 5, R6). 이벤트에 계획 컬럼이 없을 때도 같다 |
| 보존 기간이 지나 마지막 위치 뒤 변경이 지워짐 | Debezium "no longer available on the server" → "kdms reset --yes 뒤 전체 적재부터" 안내, 종료 코드 5(T-C10) |
| `sys.sp_cdc_help_jobs` | db_owner 만 부를 수 있어 경고가 난다. Debezium 이 기본 폴링 간격으로 계속하므로 로그 수준을 ERROR 로 올려 숨겼다 |

## 7. 원천 권한 (R1)

`test/sql/mssql/20_grant_kdms_login.sql` 그대로로 스트리밍·재시작·drain 이 됐다(클라우드, 로그인 `kodong_ms`):
`db_datareader`, CDC 게이팅 역할 `kdms_cdc_reader`, `cdc` 스키마 SELECT, `VIEW DEFINITION`, `VIEW DATABASE STATE`(DB), `VIEW SERVER STATE`(서버, drain 의 `sys.dm_cdc_log_scan_sessions`·Agent 상태). 쓰기 권한은 없다.
RDS for SQL Server 에서도 같은 권한으로 되는지는 2차(RDS) 때 확인한다.

## 8. 시험

| 시험(plan.md §8.2) | 어디서 | 결과 |
|---|---|---|
| T-C01 쓰기 중 적재 + 반영 → 쓰기 중지 → 검증 | 클라우드 종단(`runs/…_p4_cloud_e2e.txt`) | 30/30 |
| T-C03 입력 직후 삭제 / T-C04 트리거 이력 / T-C12 긴 트랜잭션 | `30_writes.sql` 이 섞어 넣는다 | 위 검증에 포함 |
| T-C05 같은 행 적재·CDC 값 동일 | `CdcValuesTest`, 종단 검증 해시 | 통과 |
| T-C06 LOB 미변경 UPDATE | `30_writes.sql`(view_cnt 만 수정), 종단 검증 | body 유지, 해시 일치 |
| T-C07 PK 변경 | `30_writes.sql`(code_master 코드 X 붙이기·떼기) | 해시 일치 |
| T-C08 강제 종료 후 재시작(수집 중·반영 중) | 종단 시험의 `kill -9` | 이어 받고 30/30 |
| T-C10 보존 기간 초과 | `sp_cdc_cleanup_change_table` 로 위치 뒤를 지운 뒤 `kdms sync` | 종료 코드 5, reset 안내 |
| T-C02 워터마크를 적재 뒤에 기록 | 실험 빌드 대신 거부로 막았다(`kdms load` 는 워터마크 없으면 거부) | — |
| T-C09 SQL Agent 중지·재시작 | 캡처 Job 을 60초 멈췄다 다시 시작(`msdb.dbo.sp_stop_job`/`sp_start_job`, 쓰기 중) | `runs/…_p4_cloud_tc09.txt`. 노트북에서 SQL Agent 서비스로도 한 번 |
| T-C11 전환 | 5단계 | — |

원천 쓰기 스크립트: `test/sql/mssql/30_writes.sql`(KIS `sql/50_cdc/11_mssql_writes.sql` 방식, `DURATION_SEC` 초 동안 rating·issuer·research_doc·app_user·code_master·daily_count 에 입력·수정·삭제·PK 변경·LOB 미변경 수정·입력 직후 삭제·여러 테이블 트랜잭션·MERGE, 500 번째마다 3초 트랜잭션). 노트북 절차는 [test-env.md](test-env.md) §10.
