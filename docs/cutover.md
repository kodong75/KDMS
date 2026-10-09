# 전환과 화면 (5단계)

> 상태: 완료 · 최종 갱신: 2026-10-09 · a43a5f8 · 근거: PR #10(5단계 머지 2026-10-08), WORKLOG 2026-10-08 13:10·15:44

[plan.md](plan.md) §6 5단계: 전환 상태 기계, `setval`, FK, 다운타임 측정, 웹 화면(진행률·지연·검증), CLI 전 명령.
전환 절차의 근거는 plan.md §4.5(KIS:docs/zero-downtime-rehearsal.md §4). 변경분 동기화는 [cdc.md](cdc.md), 적재·검증은 [load-verify.md](load-verify.md).

## 1. 전체 순서

```
kdms schema                     대상 테이블·PK
kdms sync          (계속 띄움)   워터마크 기록 → 변경 수집·반영
kdms load                       원천 쓰기가 있는 동안 전체 적재. 끝난 테이블부터 sync 가 반영
   … 지연이 0 근처에서 유지되는지 본다(kdms status, 웹 화면) …
원천 앱 쓰기 중지(사람)          ← 여기서부터 다운타임
kdms sync 중지(Ctrl+C)
kdms cutover --yes              마지막 반영 → PK 없는 테이블 재적재 → UNIQUE·인덱스 → 검증 → setval → FK
앱을 대상 PG 로 전환(사람)       ← 여기까지 다운타임. kdms 가 재는 것은 cutover 시작~끝
```

웹 화면에서는 같은 일을 버튼으로 한다(§4). 화면의 "전환 시작" 은 그 화면에서 띄운 동기화를 먼저 멈추고 전환한다.

## 2. `kdms cutover`

| 단계 | 하는 일 | 다시 실행하면 |
|---|---|---|
| ① 마지막 반영 | `kdms sync --drain` 과 같다(cdc.md §5): 캡처 Job 이 전환 시작 뒤 로그를 한 번 다 훑었고, 원천 마지막 커밋까지 반영했고, 그 뒤 커밋이 없으면 끝. plan.md §4.5 ②(캡처 지연 확인)·③(0건 2회)를 이것으로 한다 | 같은 위치에서 바로 끝남 |
| ② PK 없는 테이블 재적재 | 변경분을 반영하지 않은 PK 없는 테이블을 비우고 다시 적재(plan.md §4.6). 없으면 건너뜀 | 다시 비우고 적재 |
| ③ UNIQUE·인덱스 | 반영 중 미뤄 둔 적재 뒤 DDL(cdc.md §4) | 이미 있는 것은 건너뜀 |
| ④ 검증 | `kdms verify` 와 같다. **불일치면 여기서 멈춘다**(setval·FK 안 함). 작업 VERIFIED | 다시 계산 |
| ⑤ IDENTITY·SEQUENCE | 원천 값을 이 자리에서 다시 읽어 `setval`(아래) | 같은 값 |
| ⑥ FK | `NOT VALID` 없이 만들어 기존 행도 검사 | 이미 있는 것은 건너뜀 |

작업 상태: `SYNCING`(또는 `--no-cdc` 적재 뒤 `LOADING`) → `CUTOVER` → ④ 통과 `VERIFIED` → 끝 `DONE`. 어느 단계든 실패하면 `FAILED` 와 `kdms.job.last_error`.
`FAILED` 에서는 원인을 고친 뒤 `kdms cutover --yes` 를 다시 하거나, 원천 쓰기를 다시 열어야 하면 `kdms sync` 로 동기화를 이어 간다(③ 의 UNIQUE 가 이미 있으므로 반영 순서에 따라 잠깐 UNIQUE 가 겹치면 반영이 멈출 수 있다. 그때는 해당 인덱스를 지우고 이어 간다).
중간에 프로세스가 죽으면(`CUTOVER`·`VERIFIED` 에 남음) 그대로 `kdms cutover --yes` 를 다시 실행한다. 모든 단계가 다시 해도 같은 결과라 처음 단계부터 다시 돈다(S3).

| 옵션 | 뜻 |
|---|---|
| `--yes` | 원천 앱 쓰기를 멈췄다는 확인. 없으면 거부(KDMS 는 앱을 멈추지 않는다) |
| `--max-wait 초` | ① 이 이 시간 안에 끝나지 않으면 멈춘다(기본 600). 쓰기가 남아 있을 때 무한히 기다리지 않게 |
| `--no-cdc` | `kdms load --no-cdc` 로 적재한 작업(워터마크 없음): ① 을 건너뛴다. 워터마크가 있으면 거부 |

종료 코드: 0 전환 끝, 1 설정 오류, 2 접속 실패, 3 계획에 오류, 4 시작 거부, 5 검증 불일치·단계 실패, 6 마지막 반영 시간 초과.

시작 거부(4): `--yes` 없음 · 작업 없음 · 이미 `DONE` · 적재가 안 끝난 테이블 · 워터마크 없음(→ `--no-cdc`) · `kdms sync`·`load`·다른 `cutover` 실행 중(PG advisory lock).
반대로 전환 중에는 `kdms sync`·`load`·`reset`·`schema`(적재 전)가 거부된다(상태 `CUTOVER`·`VERIFIED`, 잠금 `kdms.cutover:<작업>`).

### 2.1 IDENTITY·SEQUENCE (KIS:docs/issues.md A09, T-L05·T-L06)

| 대상 | 원천 값 | 대상 다음 값 |
|---|---|---|
| IDENTITY | `sys.identity_columns.last_value`(= `IDENT_CURRENT`). 삭제·RESEED 로 `MAX(id)` 보다 클 수 있어 MAX 를 쓰지 않는다 | `IDENT_CURRENT + 증가값`. 한 번도 안 썼으면 시작값 |
| IDENTITY, 대상 MAX 가 더 앞섬 | (IDENTITY_INSERT 로 넣은 행) | 대상 `MAX + 증가값`, 출력에 이유를 적는다 |
| SEQUENCE | `sys.sequences.current_value`, `last_used_value` 가 NULL 이면 안 쓴 것 | `current_value + 증가값`, 안 썼으면 `start_value` |

원천 값은 계획 때가 아니라 ⑤ 에서 다시 읽는다(쓰기를 멈춘 뒤의 값). 원천 권한은 4단계와 같다(`VIEW DEFINITION` 으로 `sys.identity_columns`·`sys.sequences` 를 읽는다).

### 2.2 다운타임과 기록

- `kdms.cutover_run`: 전환 한 번(시작·끝·`elapsed_ms` = 예상 다운타임·검증 run_id·오류), `kdms.cutover_step`: 단계별 상태·소요 ms·요약(숫자와 이름만, 행 값 없음). 관리 스키마 버전 3.
- 출력 끝의 표와 `소요 시간(예상 다운타임) N초`. 실제 다운타임은 여기에 "앱 쓰기 중지 → cutover 시작" 과 "cutover 끝 → 앱 전환" 의 사람 작업 시간이 더해진다.
- 끝나면 "전환 뒤 사람이 할 일" 로 원천 트리거(PL/pgSQL 로 배포, 반영이 끝났으므로 지금 켜도 이력이 두 번 생기지 않는다, G13)와 뷰·SP·함수·SYNONYM 목록을 낸다(KIS:docs/appcompat.md, plan.md §3.2).

## 3. `kdms status`

접속 확인 뒤에 작업 진행을 붙였다(웹 화면과 같은 값, `kdms.state.JobView`):

```
[작업] kdms_mock (job_id 1)
  상태                DONE
  전체 적재           테이블 7개 중 7개 끝, 적재한 행 48,205
  변경분 반영         수집 2,674 · 반영 2,674 · 대기 0 · 지연 0.0초 (2026-10-08T04:12:51 기준)
  검증                run 1: 항목 30개 중 일치 30 · 불일치 0
  전환                cutover 2: DONE · 소요 6.8초
```

`실행 중: kdms sync` 처럼 지금 그 작업을 돌리는 명령도 보인다(다른 터미널·다른 PC 포함, `pg_locks` 의 advisory lock 으로 본다. 잠금을 잡아 보지 않으므로 막 시작하는 명령과 부딪히지 않는다).

## 4. 웹 화면 (`kdms web`)

`http://127.0.0.1:8080`(설정 `web.address`·`web.port`). 3초마다 대상 PG 의 관리 테이블을 읽어 그린다. 외부 CDN·라이브러리 없음(T-N02).

| 영역 | 내용 |
|---|---|
| 단계 흐름 | 스키마 → 전체 적재 → 변경분 반영 → 전환 → 검증 통과 → 완료. 지금 단계를 진하게 |
| 변경분 반영(CDC) | 지연(초), 수집·반영·대기, 원천 마지막 변경 시각, `kdms sync` 실행 여부와 마지막 갱신 |
| 전체 적재 | 끝난 테이블 수·막대, 적재한 행 |
| 검증 | 마지막 검증의 일치 항목 수(불일치면 빨강) |
| 전환 | 예상 다운타임, 단계별 상태·소요 시간, 멈춘 이유 |
| 실행 | 동기화 시작·중지, 전체 적재, 검증, 전환 시작(확인 창)·중지. 명령 출력이 아래에 나온다 |
| 테이블 | 테이블별 상태, 적재 구간 막대, 적재 행, 반영 수, 대기, 검증 결과 |
| 접속 | 원천·대상 접속·버전(1단계 화면) |
| 기록 | `kdms.event_log` 최근 15건 |

- 버튼은 CLI 와 같은 명령 클래스를 같은 프로세스 안에서 실행한다. 거부·잠금·종료 코드가 같다. 종류마다 하나씩만 돈다.
- 지우는 명령(`reset`, `schema --replace`)은 버튼이 없다. 명령줄에서만.
- 명령 실행 요청(POST)은 화면을 열 때 받은 토큰을 헤더(`X-KDMS-Token`)로 보내야 한다. 다른 사이트가 이 주소로 몰래 보내는 요청(CSRF)은 사용자 정의 헤더를 붙일 수 없어 403. 인증은 없으므로 `web.address` 를 127.0.0.1 밖으로 열 때는 신뢰하는 망에서만.
- API: `GET /api/job`(작업 상태 JSON), `GET /api/tasks`(화면에서 실행한 명령과 출력 마지막 400줄), `POST /api/tasks/{sync|load|verify|cutover}`, `POST /api/tasks/{sync|cutover}/stop`, `GET /api/status`(접속).
- 웹 포트 설정이 무시되고 늘 8080 이던 문제를 고쳤다(기본 속성이 `application.yml` 보다 우선순위가 낮았다 → 명령줄 인자로 넘김).

## 5. 4단계에서 넘어온 것

**적재 전 지연이 크게 보이던 문제**([issues.md](issues.md) B06, DEC-36): 아직 반영한 변경이 없으면 지연을 워터마크 커밋 시각부터 재서, 적재 전에는 지연이 수백 초로 보였다(노트북 실측 348초).
지금은 지연 = 원천 마지막 커밋 시각 − **반영할 수 있는(적재가 끝난 테이블의) 가장 오래된 미반영 변경**의 커밋 시각. 그런 변경이 없으면 Debezium 이 읽은 위치의 시각(수집 지연)이다.
적재 전 테이블의 변경은 지연이 아니라 진행 줄의 `대기 N(적재 전 테이블 n개, 그 변경 m)` 로 따로 센다. 둘 다 원천 시계(`cdc.lsn_time_mapping`).

**재시작 뒤 쓰기가 없으면 drain 이 끝나지 않던 문제**(클라우드 실측): `kdms sync` 를 멈췄다 다시 띄웠을 때 그 사이 원천 커밋이 없으면 SQL Server 커넥터가 하트비트를 보내지 않아 "스트리밍 시작 전" 에 머물렀다.
저장된 Debezium 오프셋이 트랜잭션 경계(`change_lsn` 없음)에 있으면 그 `commit_lsn` 을 처리 위치로 삼고 시작한다. 오프셋은 `change_log` 커밋 뒤에만 저장하므로 그 위치까지의 변경은 이미 받았다.

## 6. 시나리오 (plan.md §6 5단계 완료 기준 "S1~S4")

계획서에 S1~S4 의 정의가 없어 이 단계에서 정했다(DEC-38).

| 시나리오 | 하는 일 | 합격 기준 |
|---|---|---|
| S1 정상 전환 | 쓰기 중 적재·반영 → 쓰기 중지 → `kdms sync` 중지 → `kdms cutover --yes` | 종료 코드 0, 검증 30/30, 작업 DONE, 소요 시간 출력(T-C01·T-C11) |
| S2 불일치면 전환하지 않음 | S1 처럼 준비한 뒤 대상 한 행을 바꾸고 `cutover` → 고친 뒤(`load --reset -t 그 테이블`) 다시 `cutover` | 첫 번째 종료 코드 5, ④ 에서 멈춤, FK 0개(⑤⑥ 안 함), FAILED. 두 번째 0·DONE |
| S3 중단과 재실행 | 쓰기가 남은 채 `cutover --max-wait 15` → 쓰기 끝 → `cutover` 를 ④ 에서 `kill -9` → 다시 `cutover` | 첫 번째 종료 코드 6, 강제 종료 뒤 상태 CUTOVER, 다시 실행하면 0·DONE·30/30 |
| S4 전환 뒤 새 입력 | S1 뒤 대상에 IDENTITY 테이블 입력, SEQUENCE nextval, 없는 부모를 가리키는 FK 입력 | 새 id = 원천 `IDENT_CURRENT + 1`, 모든 IDENTITY 다음 값 > MAX, FK 위반 오류(T-L05·T-L06·T-C11) |

클라우드 결과: [WORKLOG.md](../WORKLOG.md) 5단계, 원문 `runs/*_p5_cloud_*.txt`. 노트북 절차: [test-env.md](test-env.md) §11.
