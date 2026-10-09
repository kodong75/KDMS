# 검증용 값 정규화 규칙 (MS-SQL ↔ PostgreSQL)

> 상태: 확정 · 최종 갱신: 2026-10-09 · a43a5f8 · 근거: KIS:docs/normalization.md, PR #8, 검증 30/30(WORKLOG 2026-10-08 11:00)

KIS:docs/normalization.md 를 **그대로** 옮기고 KDMS 구현 위치를 적은 것이다(plan.md §4.7). 규칙을 바꾸면 KIS 문서·이 문서·코드를 함께 고친다.
운영 방법은 [load-verify.md](load-verify.md).

| 구현 | 위치 |
|---|---|
| 컬럼 → 원천 T-SQL 식 / 대상 PG 식 | `kdms.verify.Normalizer.column` |
| 행 문자열·해시·묶음 번호·합계 식 | `Normalizer.sourceRow/targetRow`, `sourceHash/targetHash`, `sourceBucket/targetBucket`, `sourceSum/targetSum` |
| 실행·비교·행 차이·저장 | `kdms.verify.Verifier` |
| 같은 규칙의 적재 쪽 값 변환 | `kdms.load.CopyValues` |
| 시험 | `NormalizerTest`(식, T-L17), `CopyValuesTest`, `LoadVerifyIT`(실제 두 DB) |

## 0. 원칙

- **정규화는 "표현"만 맞춘다. 값 차이는 숨기지 않는다.** 끝 공백·대소문자·NUL 손실처럼 실제로 달라진 값은 해시가 달라져야 한다. 예외는 타입 자체가 값을 바꾸는 `char(n)` 채움 공백뿐이다.
- 원천·대상 계산은 각 DB 에서 하고 숫자(건수·해시 합·합계)만 앱으로 가져와 비교한다. KIS 는 tds_fdw 로 PG 한 곳에서 비교했고 KDMS 는 JDBC 두 개로 같은 일을 한다(대상 PG 에 확장이 필요 없다).
- 행 해시 = MD5(컬럼 정규화 문자열을 `|` 로 이은 것의 **UTF-8 바이트**) 앞 8바이트를 부호 있는 bigint 로 → 테이블 해시 = 합(decimal(38,0)). 순서와 무관하고 한 행이라도 다르면 합이 바뀐다.
- NULL 은 `\N` 두 글자. **`ISNULL` 대신 `COALESCE`**(KIS D01: `ISNULL(char(26) 식, N'\N')` 이 첫 인수 타입으로 잘림). 생성 SQL 에 ISNULL 이 없음을 `NormalizerTest` 가 확인한다(T-L17).
- **규칙이 값을 바꾸면 원천 쪽에 같은 규칙을 적용해 비교한다**(대상에는 이미 바뀐 값이 있다). 적용 순서는 적재와 같다: NUL(strip/replace) → 끝 공백(rtrim) → 대소문자(upper/lower), 날짜는 센티널(null/infinity), 시간형은 반올림(half_up/truncate).
- 계산 컬럼도 해시·합계에 넣는다(대상 `GENERATED … STORED` 식이 원천과 같은 값을 내는지까지 확인된다).

## 1. 타입별 정규화 문자열

| MS-SQL 원천 | MS-SQL 정규화 식 | PG 정규화 식 | 비고 |
|---|---|---|---|
| varchar, nvarchar, (max), text, ntext, sysname | `CAST(x AS nvarchar(max))` | `x::text` | 끝 공백·대소문자 유지 → 달라지면 해시로 잡힘 |
| char(n), nchar(n) | `RTRIM(CAST(x AS nvarchar(max)))` | `rtrim(x::text)` | A10. 두 DB 모두 char 비교에서 끝 공백 무시 |
| int, bigint, smallint, tinyint | `CONVERT(varchar(20), x)` | `x::text` | |
| bit | `CASE x WHEN 1 THEN '1' WHEN 0 THEN '0' END` | boolean: `CASE WHEN x THEN '1' WHEN NOT x THEN '0' END`, 그 밖: `x::text` | |
| decimal(p,s), numeric | `CONVERT(varchar(50), x)` | `x::text` | 스케일 유지 |
| money / smallmoney | `CONVERT(varchar(50), CAST(x AS decimal(19,4)))` / `decimal(10,4)` | `x::text` | `CONVERT(varchar, money)` 는 소수 2자리로 반올림 |
| float | `CONVERT(varchar(60), CAST(x AS decimal(38,10)))` | `round(x::numeric, 10)::text` | |
| real | 위와 같음 | `round(x::float8::numeric, 10)::text` | KDMS 추가: `real::numeric` 은 유효 6자리로 잘린다(KDMS_MOCK 에는 real 컬럼 없음, 미실측) |
| date | `CONVERT(char(10), x, 23)` | `to_char(x, 'YYYY-MM-DD')` | |
| datetime | `CONVERT(char(23), x, 121)` | `to_char(x, 'YYYY-MM-DD HH24:MI:SS.MS')` | A05. `DATEPART(ms)` 금지 |
| smalldatetime | `CONVERT(char(19), x, 120)` | `to_char(x, 'YYYY-MM-DD HH24:MI:SS')` | |
| datetime2(p) | `CONVERT(char(26), CAST(x AS datetime2(6)), 121)` | `to_char(x, 'YYYY-MM-DD HH24:MI:SS.US')` | A06. 7자리 → 6자리 반올림 |
| datetimeoffset(p) | `CONVERT(char(26), CAST(SWITCHOFFSET(x, '+00:00') AS datetime2(6)), 121)` | `to_char(x AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US')` | 같은 순간(UTC) |
| time(p) | `CAST(CAST(x AS time(6)) AS char(15))` | `to_char('2000-01-01'::date + x, 'HH24:MI:SS.US')` | |
| uniqueidentifier | `LOWER(CONVERT(char(36), x))` | `x::text` | |
| rowversion, varbinary, binary, image | `CONVERT(varchar(max), CAST(x AS varbinary(max)), 2)` | `upper(encode(x, 'hex'))` | 16진 대문자 |
| xml | `CAST(x AS nvarchar(max))` | `x::text` | KDMS 추가. 직렬화 표현이 달라 해시가 다를 수 있다(보고서 "참고") |

원천 행 문자열은 `nvarchar(max)` 로 이어 붙인 뒤 `COLLATE Latin1_General_100_BIN2_UTF8` 로 `varchar(max)` 변환해 UTF-8 바이트를 만들고 `HASHBYTES('MD5', …)` 한다(SQL Server 2019+). PG 는 DB 인코딩이 UTF8 이므로 `md5(text)` 가 곧 UTF-8 바이트 MD5 다.

## 2. 값 규칙을 원천 쪽에 적용하는 식 (KDMS 추가)

| 규칙 | 원천 식 | 적재(`CopyValues`) |
|---|---|---|
| `nul_char: strip` / `replace` | `REPLACE(CAST(x AS nvarchar(max)) COLLATE Latin1_General_100_BIN2_UTF8, NCHAR(0), N'' / N'�')` | `replace("\0", "" / "�")` |
| `trailing_space: rtrim` | `RTRIM(…)` (공백만) | 끝의 U+0020 만 지움 |
| `case: upper` / `lower` | `UPPER(…)` / `LOWER(…)` | `toUpperCase(Locale.ROOT)` / `toLowerCase` |
| `sentinel_dates.action: null` | `CASE WHEN CAST(x AS date) IN (센티널…) THEN NULL ELSE … END` | `\N` |
| `sentinel_dates.action: infinity` | 1970 이전 센티널 `'-infinity'`, 이후 `'infinity'` / PG 쪽도 `CASE WHEN x = 'infinity' …` | `-infinity` / `infinity` |
| `round: truncate` (시간형) | `DATEADD(NANOSECOND, -(DATEPART(NANOSECOND, x) % 1000), x)` 뒤 `CAST … (6)` | 마이크로초 아래 버림 |

NUL 치환의 콜레이션은 행 해시의 UTF-8 콜레이션과 같아야 한다(load-verify.md §4).
`UPPER`/`LOWER` 는 ASCII 밖 문자(예: 독일어 ß)에서 SQL Server 와 Java 결과가 다를 수 있다. `case` 규칙은 코드성 컬럼에만 쓴다.

## 3. 합계(수치 컬럼)

- int 계열·decimal·numeric·money·smallmoney 컬럼마다 원천 `SUM(CAST(x AS decimal(38, 스케일)))`(money·smallmoney 는 4, 정수는 0) ↔ 대상 `sum(x)::numeric`. `SUM(money)` 는 money 로 누적돼 8115 오버플로가 난다(A04, T-L03).
- float·real·bit 는 합계에서 뺀다(해시에만).

## 4. 건수

`COUNT_BIG(*)` ↔ `count(*)`.

## 5. 행 차이 찾기 (KDMS 추가)

묶음 번호 = PK 정규화 문자열(위 식, `|` 로 이음)의 MD5 앞 2바이트(0~65535).
원천 `CAST(SUBSTRING(HASHBYTES('MD5', CAST((pk) COLLATE Latin1_General_100_BIN2_UTF8 AS varchar(max))), 1, 2) AS int)` ↔ 대상 `('x' || substr(md5(pk), 1, 4))::bit(16)::int`.
정렬 순서에 기대지 않으므로 원천 CI 콜레이션·대상 C 콜레이션이어도 같은 행은 같은 묶음이다. 절차는 load-verify.md §3.

## 6. 콜레이션·대소문자·끝 공백 (값은 같아도 조회 결과가 달라지는 것)

해시가 같아도 앱 조회 결과는 달라질 수 있다(KIS normalization §4, B01~B14). `kdms plan`·`kdms verify` 보고서의 "주의" 절에 규칙 결정과 함께 나온다.

## 7. 해시로만 잡히는 손실 (건수·합계는 같음)

- **NUL 문자**: KIS(tds_fdw)는 NUL 앞에서 조용히 잘렸다. KDMS 는 `nul_char: fail` 이면 적재 단계에서 멈추고, strip/replace 면 원천 쪽에 같은 치환을 해 비교한다(load-verify.md §4).
- **CP949 로 표현 못 하는 문자**: 원천 varchar 에 이미 `?` 로 저장된 값은 그대로 `?` 라 해시가 같다 → 원천 단계 손실이라 검증으로는 안 잡힌다(A03).
