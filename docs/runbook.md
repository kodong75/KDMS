# KDMS 운영 절차서

MS-SQL 2019 → PostgreSQL 16 이관을 KDMS 로 처음부터 끝까지 하는 순서다. [plan.md](plan.md) §6 6단계 완료 기준 "절차서만 보고 다시 할 수 있음" 의 그 절차서.
명령의 자세한 뜻은 각 단계 문서([schema-conversion.md](schema-conversion.md), [load-verify.md](load-verify.md), [cdc.md](cdc.md), [cutover.md](cutover.md))에 있고, 여기서는 **누가, 언제, 무엇을 실행하고, 무엇을 보고 다음으로 가는지**만 적는다.
시험 환경(Mac + 노트북)에서 이 절차를 그대로 두 번 해 보는 리허설은 [rehearsal.md](rehearsal.md).

---

## 0. 한눈에

```
 D-n  준비        DBA: 원천 CDC·권한·스냅숏 격리, 대상 DB·역할      이관 담당: 설정·status·plan 검토
 D-n  적재·동기화  이관 담당: schema → sync(계속 띄움) → load → 지연 감시(며칠 가도 된다, 보존 기간 안에서)
 D-day 전환       앱 담당: 쓰기 중지 ─┐
                  이관 담당: sync 중지 → cutover --yes → check --probe      ← 예상 다운타임(kdms 가 잰다)
                  DBA: 트리거·뷰·SP·권한 배포                              
                  판단: 계속 / 되돌리기(§8)  →  앱 담당: 접속을 대상으로 ─┘  ← 실제 다운타임 끝
 D+n  정리        DBA: 원천 CDC 끄기 · 이관 담당: 기록 보관
```

| 역할 | 하는 일 | 권한 |
|---|---|---|
| DBA(Database Administrator, DB 관리자) | 원천 CDC(Change Data Capture, 변경 데이터 캡처) 켜기·끄기, 로그인·권한, 대상 DB·역할, 전환 뒤 트리거·뷰·SP(Stored Procedure, 저장 프로시저)·권한 배포 | 원천 sysadmin(또는 RDS 마스터), 대상 superuser |
| 이관 담당 | `kdms` 명령 실행, 감시, 기록 | 원천 읽기 전용 로그인, 대상 `kdms_app`(테이블 소유자, superuser 아님) |
| 앱 담당 | 원천 앱 쓰기 중지, 앱 접속을 대상으로 전환, 전환 뒤 앱 확인 | 앱 설정 |

---

## 1. 준비물

| 무엇 | 내용 | 확인 |
|---|---|---|
| 실행 PC | Java 21(7단계 설치본이면 필요 없음), `kdms.jar`. 원천 1433·대상 5432 로만 나간다(인터넷 필요 없음, T-N01~03) | `java -version` |
| `.env` | 접속 정보와 비밀번호. 저장소에 올리지 않는다. 형식은 `.env.example` | 비밀번호가 명령줄·기록에 나오지 않는다 |
| `config/kdms.yml` | 작업 이름, 원천·대상(환경 변수 자리표시), 병렬도, 대상 테이블, 규칙 파일 경로. `config/kdms.example.yml` 을 복사 | `kdms status` 가 설정 오류 없이 돈다 |
| 규칙 파일 | 작업별 변환 결정(계산 컬럼 PG 식, NUL·끝 공백·대소문자 처리). `config/kdms-rules.yml` 이 예 | §4 계획 검토에서 확정 |
| 디스크 | 원천 트랜잭션 로그: CDC 캡처가 멈추면 잘리지 않는다(R2). 대상: 원천 데이터 + 인덱스 + `kdms.change_log`(반영 뒤 바로 지움) | DBA 가 로그 여유를 본다 |

---

## 2. DBA 준비 (D-n)

### 2.1 원천 MS-SQL

시험 환경의 스크립트가 그대로 틀이다(DB 이름만 바꾼다). RDS 에서 다른 곳은 각 파일 머리말의 `RDS` 줄.

| 순서 | 무엇 | 틀 | 확인 |
|---|---|---|---|
| ① | SQL Server Agent 실행(캡처·정리 Job 이 Agent 로 돈다), 시작 유형 자동 | — | `kdms status` 의 `SQL Agent Running` |
| ② | 스냅숏 격리 켜기 `ALTER DATABASE <DB> SET ALLOW_SNAPSHOT_ISOLATION ON` (전체 적재가 SNAPSHOT 으로 읽는다) | `test/sql/mssql/00_restore_kdms_mock.sql` 끝 | `스냅숏 격리 ON` |
| ③ | CDC 켜기: `sys.sp_cdc_enable_db`, PK 있는 대상 테이블마다 `sys.sp_cdc_enable_table`(게이팅 역할 `kdms_cdc_reader`, `supports_net_changes = 0`) | `10_enable_cdc.sql` | `CDC 켜짐 (캡처 테이블 N개)` = 대상 테이블 수 |
| ④ | CDC 보존 기간을 **적재 시작부터 전환까지 + 여유** 로: `EXEC sys.sp_cdc_change_job @job_type = N'cleanup', @retention = <분>` (기본 4320분 = 3일) | — | `sys.sp_cdc_help_jobs` 의 retention |
| ⑤ | 이관 로그인: 읽기 전용 + CDC 읽기. `db_datareader`, `kdms_cdc_reader`, `cdc` 스키마 SELECT, `VIEW DEFINITION`, `VIEW DATABASE STATE`, 서버 `VIEW SERVER STATE` ([cdc.md](cdc.md) §7). sa·쓰기 권한은 주지 않는다 | `20_grant_kdms_login.sql` | `kdms status` 의 `로그인` |
| ⑥ | 이관 기간 원천 스키마 동결(R6): 컬럼 추가·변경이 있으면 `kdms sync` 가 멈춘다 | — | 변경 관리 공지 |

### 2.2 대상 PostgreSQL

| 순서 | 무엇 | 틀 |
|---|---|---|
| ① | DB 와 이관 역할: `CREATE ROLE kdms_app LOGIN …; CREATE DATABASE <DB> OWNER kdms_app` (superuser·CREATEDB 아님) | `test/sql/pg/00_create_kdms_db.sql` |
| ② | 시간대: 원천 기본값 `getdate()`·`sysdatetime()` 은 `LOCALTIMESTAMP` 로 옮겨진다. 원천 Windows 시간대와 같게 `ALTER DATABASE <DB> SET timezone = 'Asia/Seoul'` (plan 보고서 경고) | — |
| ③ | 앱 역할(이관 뒤 앱이 접속할 역할)을 따로 만든다. 권한은 전환 뒤 §7 ⑤ 에서 준다 | — |

---

## 3. 설정과 접속 확인 (이관 담당)

```bash
cp .env.example .env                       # 값 채우기(따옴표 없이). 운영은 KDMS_SRC_TRUST_CERT=false + 인증서 신뢰 저장소
cp config/kdms.example.yml config/kdms.yml # job_name, tables.include/exclude, rules
java -jar kdms.jar status                  # 종료 코드 0
```

`kdms status` 에서 볼 것: 원천·대상 `접속 OK`, 원천 `CDC 켜짐 (캡처 테이블 N개)`, `스냅숏 격리 ON`, `SQL Agent Running`, 대상 `인코딩 UTF8`. 하나라도 다르면 §2 로 돌아간다. 막히면 [test-env.md](test-env.md) §7 표.

---

## 4. 계획 검토 (D-n, 이관 담당 + DBA + 앱 담당)

```bash
java -jar kdms.jar plan --scan             # out/<작업>/plan.txt 와 DDL 3개. 아무 DB 도 바꾸지 않는다. --scan 은 원천을 한 번 훑는다(업무 시간 밖에)
```

| 보고서 절 | 뜻 | 할 일 |
|---|---|---|
| `== 오류 ==` | 이대로는 옮길 수 없다(계산 컬럼 식 없음, NUL 문자 등). 종료 코드 3 | 규칙 파일로 결정해 다시 `plan`. 오류 0 이어야 다음 단계가 돈다 |
| `== 경고 ==` | 옮기지만 사람이 확인할 것(기본값 함수 차이, 정렬 순서, rowversion, 트리거) | 항목마다 승인·조치를 기록 |
| `== 주의 ==` | 값은 같지만 조회 결과가 달라지는 곳(대소문자, 끝 공백, 바이트 길이, 센티널 날짜. KIS B01~B14) | **앱 담당에게 전달**. 앱 쿼리 수정 여부 결정 |
| `자동 변환하지 않는 객체` | 뷰·SP·함수·SYNONYM·트리거 | DBA 가 PG 판을 미리 만든다(KIS:docs/appcompat.md). §7 ⑤ 에서 배포 |

규칙을 바꾸면 `plan` 을 다시 하고, 바뀐 결정은 규칙 파일 주석에 근거와 함께 남긴다. 계획이 확정되면 `config/` 와 `out/<작업>/plan.txt` 를 보관한다.

---

## 5. 스키마 · 동기화 · 전체 적재 (D-n, 이관 담당)

창 두 개(서버라면 `tmux`·`screen` 등 끊겨도 도는 세션)를 쓴다. 창 A 는 전환 직전까지 계속 띄워 둔다.

```bash
# 창 B
java -jar kdms.jar schema                  # 대상 테이블·PK. 이미 있으면 건드리지 않는다(--replace 는 데이터까지 지운다)
# 창 A
java -jar kdms.jar sync                    # "워터마크 기록: … 이제 다른 터미널에서 kdms load" 가 나올 때까지 기다린다
# 창 B (워터마크 뒤)
java -jar kdms.jar load                    # 원천 부하를 줄이려면 --throttle-ms 200 처럼. 끝에 "실패 0개"
```

- `load` 는 워터마크 없이 시작하지 않는다(종료 코드 4, T-C02). 원천 쓰기가 전혀 없는 이관만 `load --no-cdc` → §7 의 `cutover --no-cdc`.
- `load` 가 중간에 죽거나 멈추면 같은 명령을 다시 한다. 끝난 구간은 건너뛴다(T-L16). 한 테이블만 처음부터: `load --reset -t 스키마.테이블`.
- `sync` 가 죽으면 같은 명령을 다시 한다. 저장된 위치 다음부터 이어 받는다(`저장된 오프셋 다음부터 이어 받는다`, T-C08).

### 5.1 감시 (적재부터 전환 전까지)

`java -jar kdms.jar status` 또는 `java -jar kdms.jar web`(브라우저 `http://127.0.0.1:8080`)으로 본다.

| 볼 것 | 정상 | 아니면 |
|---|---|---|
| 지연(창 A 진행 줄 `지연 N초`) | 0 근처에서 유지 | 늘기만 하면 반영이 못 따라간다: 원천 쓰기량·대상 부하 확인 |
| `원천 캡처 Job 이 N초째 로그를 읽지 않음` | 안 나옴 | SQL Agent·캡처 Job 이 멈췄다 → DBA 가 다시 시작(T-C09). 다시 시작하면 따라잡는다 |
| `원천 로그 REPLICATION 대기` | 안 나옴(잠깐은 괜찮다) | 계속되면 원천 로그가 잘리지 않아 디스크가 찬다(R2) → 캡처 Job 확인 |
| 대기(반영 대기 건수) | 0 근처 | 계속 늘면 반영 오류 → 창 A 의 오류 줄 |
| 창 A 가 종료 코드 5 로 끝남 | — | §9 표(원천 DDL 변경, 보존 기간 초과) |

전환 날까지 며칠 동기화만 유지해도 된다. 단 `sync` 를 멈춘 시간이 CDC 보존 기간(§2.1 ④)을 넘으면 처음부터 다시 해야 한다(T-C10).

---

## 6. 전환 전 확인 (D-day, 전환 직전)

| 확인 | 기준 |
|---|---|
| 전체 적재 | `status` 의 `전체 적재 테이블 N개 중 N개 끝` |
| 동기화 | 창 A 가 돌고, 지연 0 근처, 경고 없음 |
| 사람 준비 | 앱 담당(쓰기 중지·접속 전환 방법), DBA(트리거·뷰·SP·권한 스크립트 준비), 되돌리기 판단자(§8) |
| 시간 기록표 | 아래 §7 의 시각을 적을 표 |

---

## 7. 전환 (D-day)

| 순서 | 누가 | 무엇 | 다음으로 가는 기준 |
|---|---|---|---|
| ① | 앱 담당 | 원천 앱 쓰기 중지(서비스 중지·읽기 전용 전환). **시각 기록 = 다운타임 시작** | 앱 쓰기 0 확인 |
| ② | 이관 담당 | 창 A 에서 `Ctrl+C` (`중지 요청: 진행 중 배치를 마치고 멈춘다`) | 창 A 가 끝남 |
| ③ | 이관 담당 | `java -jar kdms.jar cutover --yes` | 종료 코드 0, `검증 항목 N개 중 일치 N · 불일치 0`, `소요 시간(예상 다운타임) N초` |
| ④ | 이관 담당 | `java -jar kdms.jar check --probe` | 종료 코드 0, `실패 0` (경고는 ⑤ 의 할 일) |
| ⑤ | DBA | 트리거·뷰·SP·함수를 PG 판으로 배포(③ 끝의 `전환 뒤 사람이 할 일`, ④ 의 경고), 앱 역할 권한(`GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES …`, `USAGE ON ALL SEQUENCES …`) | 배포 스크립트 오류 0 |
| ⑥ | 판단자 | 계속 / 되돌리기(§8) | 계속이면 ⑦ |
| ⑦ | 앱 담당 | 앱 접속을 대상 PG 로 바꾸고 기동, 핵심 화면 확인. **시각 기록 = 다운타임 끝** | 앱 정상 |

③ `kdms cutover --yes` 가 하는 일: 마지막 반영 → PK 없는 테이블 재적재 → UNIQUE·인덱스 → 검증 → IDENTITY·SEQUENCE 다음 값 → FK. 검증이 틀리면 그 자리에서 멈추고 FK·setval 을 하지 않는다([cutover.md](cutover.md) §2).

| ③ 종료 코드 | 뜻 | 할 일 |
|---|---|---|
| 0 | 전환 끝 | ④ |
| 4 | 시작 거부(`--yes` 없음, sync 가 아직 돈다, 적재 안 끝남 등. 이유가 출력된다) | 이유대로 고치고 다시 ③ |
| 5 | 검증 불일치·단계 실패. 작업 `FAILED` | 차이 행은 `kdms.verify_row_diff`(출력의 run_id). 그 테이블만 `load --reset -t 스키마.테이블` 뒤 다시 ③. 원인을 모르면 §8 되돌리기 |
| 6 | 마지막 반영이 `--max-wait`(기본 600초) 안에 안 끝남 = 원천 쓰기가 아직 있다 | ① 을 다시 확인하고 다시 ③ |

③ 이 중간에 죽으면(전원·네트워크) 그대로 다시 ③ 을 한다. 모든 단계가 다시 해도 결과가 같다.

④ `kdms check` 가 보는 것: 작업 `DONE`·마지막 전환·그 검증, IDENTITY·SEQUENCE 의 대상 다음 값 = 원천 값 다음이고 대상 MAX 보다 큼(T-L05·T-L06), PK·UNIQUE·인덱스(유효)·FK(검사 끝남)(T-C11), 계산 컬럼 `GENERATED STORED`(T-L13). `--probe` 는 대소문자만 다른 값(T-L12)·없는 부모(T-C11)를 실제로 넣어 막히는지 보고 **모두 되돌린다**(시퀀스는 쓰지 않는다). 종료 코드 0 실패 없음, 5 실패 있음.

다운타임 = ① 부터 ⑦ 까지. `kdms` 가 재는 것은 ③ 의 시간뿐이므로 ①·②·④·⑤·⑦ 의 사람 작업 시간을 리허설로 재 둔다.

---

## 8. 되돌리기

MVP 에는 역방향 동기화(대상 → 원천)가 없다(plan.md §3.2). 그래서 되돌리기는 **앱이 대상에 쓰기 전(§7 ⑦ 전)까지만** 깨끗하다.

| 언제 | 되돌리는 법 | 데이터 |
|---|---|---|
| §7 ③·④·⑤ 에서 문제 | 앱 담당이 원천 쓰기를 다시 연다. 이관 담당은 `kdms sync` 를 다시 띄워 동기화를 이어 간다(원인을 고친 뒤 다음 전환 일정) | 원천은 그대로. 손실 없음 |
| 검증 불일치 원인을 모름 | 위와 같이 원천으로 돌아간 뒤 `kdms reset --yes` → §5 처음부터 | 대상은 비우고 다시 |
| §7 ⑦ 뒤(앱이 대상에 씀) | 자동 되돌리기 없음. 대상에서 쓴 데이터를 원천으로 옮기는 작업은 별도 계획(대상 변경분 추출) | 판단자가 ⑥ 에서 결정 |

---

## 9. 장애 대응

| 증상 | 원인 → 할 일 |
|---|---|
| `sync` 종료 코드 5 `보존 기간이 지나 …` (T-C10) | 동기화를 멈춘 시간이 CDC 보존 기간을 넘었다. 이어 받을 수 없다 → `kdms reset --yes` → §5 처음부터(`sync` → `load`) |
| `sync` 종료 코드 5 원천 DDL 변경 | 스키마 동결(§2.1 ⑥)이 깨졌다 → DBA 와 원천 변경 확인. 캡처 인스턴스 재생성이 필요하면 `reset` 뒤 처음부터 |
| `sync`·`load`·`cutover` 종료 코드 4 `… 이 작업 … 을 실행하고 있다` | 다른 창·PC 에서 같은 작업 명령이 돈다(`status` 의 `실행 중:`) → 그것을 끝낸 뒤 |
| `load` 종료 코드 5 `NUL 문자` | 규칙 파일에 그 컬럼의 `nul_char` 결정이 없다(A02) → §4 로 돌아가 결정 후 `load` 다시(끝난 구간은 건너뜀) |
| `load` 가 `Snapshot isolation transaction failed … 3952` | §2.1 ② 스냅숏 격리 |
| 지연이 늘기만 함 · `캡처 Job 이 N초째 로그를 읽지 않음` | §5.1 표 |
| `verify`·`cutover` 검증 불일치(5) | §7 ③ 종료 코드 5 |
| `check` 실패: `대상 다음 값 … ≠ 기대` | 전환 뒤 원천에 쓰기가 있었거나 setval 이 빠졌다. 원천 쓰기가 멈췄는지 확인 → 원인이 setval 이면 DBA 가 출력의 기대값으로 `setval` |
| `check` 실패: 인덱스 `INVALID`·FK 없음 | 만들다 실패한 것. 원인(중복 행 등)을 고친 뒤 `DROP INDEX` → `kdms schema --phase post-load` / `--phase cutover` |
| 접속 실패(종료 코드 2) | [test-env.md](test-env.md) §7 표 |

명령마다 종료 코드: 0 정상, 1 설정 오류, 2 접속 실패, 3 계획에 오류, 4 시작 거부, 5 실패(검증 불일치·수집 반영 실패·점검 실패), 6 전환 마지막 반영 시간 초과.

---

## 10. 정리 (D+n)

| 누가 | 무엇 |
|---|---|
| 이관 담당 | 실행 출력 원문(`load`·`cutover`·`check`)과 §7 시각표를 보관. 관리 스키마 `kdms` 의 기록(`job`, `cutover_run`, `verify_run`, `event_log`)은 대상에 남는다(행 값 없음, R8) |
| 이관 담당 | 실행 PC 의 `.env` 삭제 |
| DBA | 원천 운영을 끝낼 때 CDC 끄기 `EXEC sys.sp_cdc_disable_db`(캡처·정리 Job 도 지워진다), 이관 로그인 비활성화 |
| DBA | 안정화 뒤 대상의 `kdms` 스키마를 지울지 결정(`DROP SCHEMA kdms CASCADE` 는 기록까지 지운다) |
