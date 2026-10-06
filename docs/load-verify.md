# 전체 적재·검증 (3단계)

[plan.md](plan.md) §6 3단계: 구간 분할 → COPY 적재 → 재시작 → 건수·합계·해시 검증 → 행 단위 차이.
값 정규화 규칙은 [normalization.md](normalization.md)(KIS:docs/normalization.md 를 옮기고 구현 위치를 적은 것).
3단계는 **원천에 쓰기가 없는 상태**를 전제한다. 쓰기가 계속 들어오는 동안의 적재(워터마크 먼저 기록 → 적재 → CDC 반영)는 4단계에서 이 적재기 위에 붙인다.

## 1. 명령

| 명령 | 하는 일 | 바꾸는 것 |
|---|---|---|
| `kdms load` | `kdms schema` 로 만든 대상 테이블에 원천을 전체 적재. 테이블 병렬 × 구간 병렬. 모두 적재되면 적재 뒤 DDL(UNIQUE·인덱스)을 적용 | 대상 테이블, `kdms.job·job_table·load_chunk·event_log` |
| `kdms load` (다시) | 끝난(DONE) 구간·테이블은 건너뛰고 나머지만 | 위와 같음 |
| `kdms load --reset` | 고른 대상 테이블을 TRUNCATE 하고 구간 기록을 지운 뒤 처음부터. 전부 고르면 작업의 설정 해시도 지금 것으로 바꾼다 | 위와 같음 |
| `kdms load -t dbo.rating` | 그 테이블만(여러 번 쓸 수 있다). 원천 이름 | |
| `kdms load --throttle-ms 100` | 구간마다 1,000행 읽을 때마다 쉰다(원천 부하 조절, 중단·재시작 시험) | |
| `kdms load --no-post-load` | 적재 뒤 DDL 을 적용하지 않는다(나중에 `kdms schema --phase post-load`) | |
| `kdms verify` | 건수·수치 합계·행 해시 비교, 다르면 PK 로 차이 행을 찾는다 | `kdms.verify_run·verify_result·verify_row_diff·event_log` 만. 원천·대상 데이터는 읽기만 |
| `kdms verify -t dbo.rating` | 그 테이블만 | |

종료 코드: 0 성공·모두 일치, 1 설정 오류, 2 접속 실패(적재 뒤 DDL 실패 포함), 3 계획에 오류, 4 거부(작업 없음·다른 `kdms load` 실행 중·상태가 SYNCING 이후), **5 실패한 테이블 있음(load) / 불일치 있음(verify)**.

설정(`config/kdms.yml` 의 `load:`): `table_parallelism`(기본 4), `chunks_per_table`(기본 2), `isolation`(기본 `snapshot`, 원천 DB 에 `ALLOW_SNAPSHOT_ISOLATION ON` 필요. 쓰기가 없을 때만 `read_committed`).

## 2. 적재

### 2.1 구간

| PK | 나누는 법 | 구간 조건(원천 T-SQL) |
|---|---|---|
| 정수 컬럼 하나 | `MIN`~`MAX` 균등 분할 | `[id] >= CAST(? AS bigint) AND [id] < CAST(? AS bigint)` |
| 그 밖(복합·문자·날짜·decimal·uniqueidentifier) | `NTILE(n) OVER (ORDER BY pk)` 로 경계값을 미리 뽑는다 | 행 값 비교를 펼친 식: `(a > ? OR (a = ? AND b >= ?))` |
| 없음, 또는 float·time·binary 등이 든 PK, `chunks_per_table: 1` | 테이블 전체가 구간 하나 | 없음 |

- 경계값은 `kdms.load_chunk.lower_bound/upper_bound` 에 원천 PK 컬럼 순서의 문자열 JSON 배열로 남는다(하한 포함·상한 미포함, null = 끝 없음). 날짜는 스타일 121·23 문자열로 읽고 `CONVERT(…, ?, 121)` 로 되돌린다(언어 설정의 날짜 해석에 기대지 않는다).
- 구간 조건은 **원천에서만** 쓴다. 대상 PG 는 콜레이션(C)이 원천(CI)과 달라 문자 PK 순서가 다르기 때문이다. 검증의 행 차이 찾기도 순서 대신 PK 해시 묶음을 쓴다(§3).
- 구간은 처음 적재할 때 한 번 정한다. `chunks_per_table` 을 바꿔도 기존 구간은 그대로 쓰고, 바꾸려면 `--reset`.
- 같은 테이블 안에서는 최대 `chunks_per_table` 개 구간이, 동시에 최대 `table_parallelism` 개 테이블이 돈다. 원천·대상 연결은 구간마다 하나씩.

### 2.2 구간 하나 (재시작의 근거)

```
원천: SNAPSHOT 격리 트랜잭션에서 SELECT(계산 컬럼 빼고) ─┐
대상: BEGIN; SET LOCAL synchronous_commit = off           │
      COPY 대상 (컬럼…) FROM STDIN (FORMAT text) <────────┘  값 변환: kdms.load.CopyValues
      UPDATE kdms.load_chunk SET status = 'DONE', row_count …
      COMMIT
```

구간 행과 `DONE` 표시가 **같은 대상 트랜잭션**으로 커밋된다. 그래서
- 프로세스가 어디서 죽어도 커밋된 구간 = DONE 구간이다. 다시 실행하면 DONE 이 아닌 구간(RUNNING·FAILED·PENDING)만 처음부터 넣는다. 범위 DELETE 가 필요 없다(대상 쪽 순서에 기대지 않는다).
- 실패한 구간은 `FAILED` 와 오류(행 값 없음, §2.4)를 남기고, 같은 테이블의 다른 구간은 계속한다. 테이블은 `FAILED`, 작업은 `LOADING` 에 `last_error`.
- 같은 작업에 `kdms load` 가 둘 돌지 않게 `pg_try_advisory_lock(hashtext('kdms.load:' || 작업))` 을 잡는다(세션 잠금이라 프로세스가 죽으면 풀린다).

작업 상태: `SCHEMA_DONE`·`LOADING`·`FAILED` 에서만 적재한다(`LOADING` 으로 바꾼다). 테이블 상태: `PENDING → LOADING → LOADED | FAILED`.
작업의 테이블이 모두 `LOADED` 가 되면 적재 뒤 DDL(UNIQUE·`lower()` 유일 인덱스·보조 인덱스)을 한 트랜잭션으로 적용한다. 이미 있는 인덱스·제약은 건너뛰므로 다시 실행해도 안전하다(`kdms schema --phase post-load·cutover` 도 같다).

### 2.3 값 변환 (`kdms.load.CopyValues`)

원천 JDBC 값을 COPY text 한 칸으로 바꾼다. 검증의 원천 정규화 식([normalization.md](normalization.md) §2)과 **같은 규칙·같은 순서**다. 둘 중 하나를 고치면 다른 쪽도 고친다.

| 원천 | COPY 값 | 근거 |
|---|---|---|
| 문자 | 값 규칙: NUL(§4) → 끝 공백(`trailing_space: rtrim` 이면 공백 U+0020 만) → 대소문자(`case`) | A02, B02·B04 |
| 탭·CR·LF·역슬래시 | `\t` `\r` `\n` `\\` 로 이스케이프. 리터럴 `\N` 도 `\\N` 이라 NULL 과 섞이지 않는다 | D02, T-L15 |
| NULL | `\N` | |
| decimal·money | `BigDecimal.toPlainString()` (지수 표기 없음, 스케일 유지) | A04 |
| bit | 대상이 boolean 이면 `t/f`, 아니면 `1/0` | A07 |
| datetime·datetime2·smalldatetime | `LocalDateTime` → 마이크로초로 반올림(`round: half_up`, 규칙이 truncate 면 버림) | A05·A06 |
| datetimeoffset | 오프셋 포함 문자열(대상 timestamptz 는 같은 순간) | |
| time(7) | 마이크로초 반올림. 23:59:59.9999995 는 `24:00:00` | |
| 센티널 날짜 | `sentinel_dates.action`: keep 그대로, null → `\N`, infinity → 1970 이전 `-infinity`·이후 `infinity` | A08 |
| uniqueidentifier | 소문자 | |
| binary·varbinary·image·rowversion | `\\x` + 16진 | |
| 계산 컬럼 | 적재하지 않는다(대상은 `GENERATED ALWAYS AS … STORED`) | B12 |

### 2.4 오류 메시지

로그·관리 테이블·화면에 행 값을 남기지 않는다(plan.md R8). `kdms.load.SafeMessage` 가 PG 오류의 `Detail: Failing row contains …`·`Where: COPY …, column x: "값"` 과 따옴표 안 값을 지우고 위치(테이블·몇 번째 행·컬럼)만 남긴다. NUL 오류는 컬럼과 행 수만 쓴다.

## 3. 검증 (`kdms verify`)

1. 테이블마다 원천과 대상에서 **동시에** 한 번씩 훑어 숫자만 가져온다: 건수, 행 해시 합, 수치 컬럼 합계. 규칙은 [normalization.md](normalization.md).
2. 비교: 건수·해시·합계마다 한 항목(`kdms.verify_result`). 숫자는 스케일과 관계없이 값으로 비교(`1.50 = 1.5000`), 빈 테이블은 NULL = NULL.
3. 건수나 해시가 다르면 행 차이를 찾는다(PK 없는 테이블은 생략).
   - 각 행의 PK 정규화 문자열 MD5 앞 2바이트로 묶음 번호(0~65535)를 내고, 묶음별 (건수, 해시 합)을 양쪽에서 비교한다. 두 DB 의 정렬 순서가 달라도 같은 행은 같은 묶음에 든다.
   - 다른 묶음의 행만 (PK, 행 해시)를 가져와 비교한다: 원천에만 = `missing`, 대상에만 = `extra`, 해시 다름 = `diff`. 다른 묶음이 200개 이하면 `WHERE 묶음 IN (…)`, 더 많으면 한 번 훑으며 앱에서 거른다.
   - `kdms.verify_row_diff` 에는 테이블마다 앞 100건의 PK(정규화 문자열 JSON 배열)만 남긴다. 다른 컬럼 값은 남기지 않는다.
4. 보고서 끝의 "주의" 절은 계획의 NOTE(B01~B14: 대소문자·끝 공백·CP949 바이트 등)다. 값은 같지만 조회 결과가 원천과 다를 수 있는 곳으로, 이관 오류가 아니라 규칙 결정이다(plan.md §8.3).

## 4. NUL 문자 결정 (A02, T-L09)

PG 문자 타입은 NUL(`U+0000`)을 저장하지 못한다. KIS 는 `dbo.issuer.issuer_nm` 1행(issuer_cd `900005`)에 NUL 을 심었다.

| 규칙 `text.nul_char` | 적재 | 검증(원천 쪽) |
|---|---|---|
| `fail` (기본) | NUL 이 든 행이 있으면 그 구간을 넣지 않고(롤백) 컬럼·행 수와 함께 실패. 나머지 구간·테이블은 계속 | — |
| `strip` | NUL 을 지운다 | `REPLACE(… COLLATE Latin1_General_100_BIN2_UTF8, NCHAR(0), N'')` |
| `replace` | `text.nul_replacement`(기본 U+FFFD `�`)로 바꾼다 | `REPLACE(…, NCHAR(0), N'�')` |

**결정(2026-10-06)**: 기본값은 `fail` 그대로 두고, KDMS_MOCK 의 `dbo.issuer.issuer_nm` 만 `config/kdms-rules.yml` 에서 `replace` 로 정했다.
- `strip` 은 "NULL문자포함" 처럼 흔적 없이 붙어 버려 나중에 어느 값이 바뀌었는지 찾을 수 없다. `replace` 는 대상에서 `WHERE issuer_nm LIKE '%' || chr(65533) || '%'` 로 찾을 수 있다.
- 다른 컬럼은 `fail` 이므로 새 NUL 이 생기면 조용히 바뀌지 않고 멈춰서 알린다. 미리 건수를 보려면 `kdms plan --scan`(`replace` 컬럼은 경고, `fail` 컬럼은 오류).
- 원천 정규화의 REPLACE 는 행 해시와 같은 UTF-8 이진 콜레이션을 쓴다. 코드페이지 1252 인 `Latin1_General_100_BIN2` 로 바꾸면 바깥에 `COLLATE … UTF8` 을 붙여도 varchar 변환에서 한글이 `?` 가 되어 그 테이블 해시가 전부 달라진다(2026-10-06 클라우드 실측).

## 5. 확인 결과

클라우드(2026-10-06, `runs/20261006_1440_p3_cloud_load_verify.txt`): 클라우드 컨테이너의 SQL Server 2019 Developer(Linux, 서버 콜레이션 `Korean_Wansung_CI_AS`)에 KIS `sql/10_mssql/10~12` 와 이 저장소 `test/sql/mssql/00·10·20` 으로 KDMS_MOCK 을 만들고, 컨테이너 안 PostgreSQL 16.15 로 적재·검증했다. **노트북 DB 가 아니다.**

| 기준(plan.md §6 3단계) | 결과 |
|---|---|
| 쓰기 없는 상태에서 MVP 테이블 전부 검증 일치 | 7개 테이블 48,047행, **검증 항목 30개 중 일치 30 · 불일치 0** (건수 7, 해시 7, 합계 16) |
| 적재 도중 죽였다 다시 실행해 이어서 끝나고 검증 일치 | `--throttle-ms 1000` 으로 5초 뒤 `kill -9` → 끝난 구간 8개만 DONE, 진행 중이던 5개 구간 행 0. 다시 실행 → 그 5개만 적재, 검증 30/30 |
| T-L09 NUL | 기본 `fail` 이면 issuer 구간 2 만 실패(종료 코드 5), `replace` 로 다시 실행하면 그 구간만 넣고 검증 일치 |
| 검증이 차이를 잡는가 | 대상에서 끝 공백 1자 추가·1ms 변경·삭제·추가 4건 → 각 PK 와 missing/extra/diff 를 정확히 찾음, 종료 코드 5 |

통합 시험 `LoadVerifyIT`(구간 하나를 커밋 직전에 실패시켜 재실행), `SourceCatalogIT`·`TargetDdlIT`·`SourceProbeIT`·`TargetSchemaIT` 도 같은 환경에서 통과(9개). 단위 시험 79개 통과.

노트북 원천·PG 에서의 확인 명령은 [test-env.md](test-env.md) §9.
