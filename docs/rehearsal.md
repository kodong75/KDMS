# MVP 리허설 (6단계)

> 상태: 진행 중 · 최종 갱신: 2026-10-10 · d142fde · 근거: PR #11, WORKLOG 2026-10-08 16:38(`runs/20261008_0738_p6_cloud_r1_*.txt`·`_0745_p6_cloud_r2_*.txt`), Mac→노트북 2회는 아직

[plan.md](plan.md) §6 6단계: **§8 시험 전체를 처음부터 2회, 두 번 모두 합격, 절차서만 보고 다시 할 수 있음.**
절차서는 [runbook.md](runbook.md). 리허설은 그 절차(§3~§7)를 시험 환경에서 그대로 밟으면서 §8 의 시험(T-L01~17, T-C01~12, T-N01~03)을 한 번에 채점한다.

```
Mac (scripts/rehearsal.sh, kdms.jar)  ──LAN──>  노트북 192.168.0.12
  창 main: 리허설 스크립트                         PowerShell 7 창 1: 되돌림·캡처 Job·T-C10 (스크립트가 명령을 알려 준다)
                                                  PowerShell 7 창 2: 원천 쓰기(5분)
```

---

## 1. 한 회차가 하는 일

회차마다 원천을 처음 상태로 되돌린 뒤 시작하므로 회차끼리 서로 영향이 없다. 한 회차 약 12~15분(쓰기 5분 포함).

| 구간 | 하는 일 (runbook 절) | 시험 |
|---|---|---|
| 0 | 인터넷이 끊겼는지 확인(LAN 은 그대로). 실행 중 `kdms.jar` 의 TCP 연결을 1초마다 기록 시작 | T-N01, T-N03 |
| 1 | 노트북: 원천 되돌림 `restore`(00 REPLACE → 10 CDC → 20 권한) · Mac: **오프라인 빌드**(`./mvnw -o -B clean package`, 단위 시험 포함) · jar 안 화면 파일의 외부 URL 검사 · `status` (§3) | T-L17, T-N02 |
| 2 | `plan --scan`(§4). 같은 계획을 NUL 규칙만 뺀 설정(`config/rehearsal/rules-nul-fail.yml`)으로도 → 막힘(종료 코드 3) | T-L08·T-L10·T-L14 보고서, T-L09 |
| L | 원천 쓰기 없이: `reset` → `schema --replace` → NUL fail 설정으로 `load --no-cdc`(종료 코드 5) → `load --no-cdc --reset --throttle-ms 1000` 6초 뒤 **kill -9** → `load --no-cdc`(끝난 구간 건너뜀) → `verify` → `cutover --yes --no-cdc` → `check --probe` | T-L01~T-L16 |
| C | 원천 쓰기 중(§5~§7): `reset` → `schema --replace` → 워터마크 없이 `load`(거부) | T-C02 |
|   | 보존 기간 초과: `sync` → 노트북 쓰기 20초 → `sync` 중지 → 노트북 `tc10`(변경 몇 건 → 모든 변경 기록 정리) → `sync`(종료 코드 5, reset 안내) → `reset` | T-C10 |
|   | 본 시험: `sync` → 노트북 쓰기 5분 시작 → `load --throttle-ms 2000` → 적재 중 `sync` **kill -9** → 다시 `sync` → 적재 끝 20초 뒤 반영 중 `sync` **kill -9** → 다시 `sync` → 노트북 캡처 Job 60초 중지·다시 시작(DEC-48) → 쓰기 끝 → `sync` 중지 → `cutover --yes` → `check --probe` → `status` | T-C01, T-C03~T-C09, T-C11, T-C12, T-L05·T-L06·T-L12 |
| 채점 | 단계 출력·종료 코드로 시험마다 합격·불합격·미시행(§4) | |

결과 파일: `runs/<시각>_p6_r<회차>_log.txt`(모든 명령 출력 원문, 비밀번호 없음), `runs/<시각>_p6_r<회차>_score.txt`(채점표). 중간 파일은 `out/rehearsal/`(커밋하지 않음).

---

## 2. 준비 (회차 시작 전, 한 번)

**노트북 PowerShell 7** (창 두 개를 열어 둔다)

```powershell
cd C:\Projects\KDMS
git fetch origin; git checkout claude/stage6-lis0lo      # 머지 뒤에는 git checkout main; git pull
Get-Service SQLSERVERAGENT                               # Running (아니면 [관리자] Start-Service SQLSERVERAGENT)
```

**Mac 창 main**

```bash
cd /Users/kodong/Projects/KDMS
git fetch origin && git checkout claude/stage6-lis0lo
./mvnw -B clean package -DskipTests                       # 인터넷이 있을 때 한 번: 오프라인 빌드에 필요한 플러그인을 받아 둔다
```

**인터넷 끊기(T-N01)**: 노트북(같은 LAN)은 닿고 인터넷만 끊는다. Mac 방화벽 pf(packet filter)로 LAN 과 자기 자신만 열고 나머지 나가는 연결을 막는다(DEC-49). 이 동안 Mac 의 채팅 앱도 끊기므로 결과는 휴대폰·노트북으로 보낸다.

```bash
printf 'set skip on lo0\npass out quick inet from any to 192.168.0.0/24\nblock drop out quick all\n' > /tmp/kdms-offline.pf
sudo pfctl -f /tmp/kdms-offline.pf -e      # 규칙 적용 + 방화벽 켜기(ALTQ 경고는 무시)
curl -m 5 -s -o /dev/null -w '%{http_code}\n' https://repo.maven.apache.org/maven2/   # 000 이면 끊긴 것
nc -vz 192.168.0.12 1433; nc -vz 192.168.0.12 5432      # 노트북은 succeeded
```

- 첫 줄 `set skip on lo0` 을 빼면 Mac 안의 127.0.0.1 연결까지 막혀 빌드의 웹 시험(`WebSmokeTest`)이 `Connect` 오류로 실패한다(issues.md E07).
- 기본 경로를 지우는 방법(`sudo route -n delete default`)은 macOS 가 몇 분 안에 경로를 다시 만들어 쓰지 않는다(issues.md E06).

되돌리기(두 회차가 끝난 뒤): `sudo pfctl -f /etc/pf.conf; sudo pfctl -d`.

---

## 3. 실행

**Mac 창 main** (회차 번호만 바꿔 두 번)

```bash
bash scripts/rehearsal.sh 1
```

스크립트가 멈추고 노트북 명령을 보여 주면 그 명령을 노트북에서 실행하고 Mac 에서 Enter 를 누른다(실패했으면 `n` Enter). 한 회차에 여섯 번이다.

| 순서 | 스크립트 안내 | 노트북 명령 (`C:\Projects\KDMS`) | 언제 Enter |
|---|---|---|---|
| 1 | 원천 되돌림 | `./scripts/Invoke-KdmsRehearsal.ps1 -Step restore` | 초록 `원천 되돌림 끝` 뒤 |
| 2 | T-C10 앞 쓰기 20초 | `./scripts/Invoke-KdmsRehearsal.ps1 -Step writes -Sec 20` | 초록 `쓰기 끝` 뒤 |
| 3 | T-C10 정리 | `./scripts/Invoke-KdmsRehearsal.ps1 -Step tc10` | 결과 경로가 `exit 0` 으로 나온 뒤 |
| 4 | 본 쓰기 5분 시작 | **창 2** 에서 `./scripts/Invoke-KdmsRehearsal.ps1 -Step writes -Sec 300` | **시작하자마자** (끝을 기다리지 않는다) |
| 5 | 캡처 Job 중지 | 창 1 에서 `./scripts/Invoke-KdmsRehearsal.ps1 -Step capture-stop` | 결과 뒤. Mac 이 60초 기다린다 |
| 6 | 캡처 Job 다시 시작 | `./scripts/Invoke-KdmsRehearsal.ps1 -Step capture-start` | 결과 뒤 |
| (7) | 쓰기 끝 확인 | 창 2 의 `쓰기 끝` | 나온 뒤 |

끝에 채점표가 나온다. `1회차 합격` 이면 바로 `bash scripts/rehearsal.sh 2`. 두 회차 모두 끝나면 인터넷을 되돌리고 결과 파일을 커밋한다(§5).

- 쓰기 시간을 바꾸려면 `KDMS_REH_WRITE_SEC=180 bash scripts/rehearsal.sh 1` (본 쓰기만. 4번 노트북 명령의 `-Sec` 도 같은 값으로).
- 중간에 멈추려면 `Ctrl+C`(스크립트가 띄운 `kdms` 만 멈춘다. 노트북 쓰기 창은 따로 `Ctrl+C`). 다시 할 때는 처음부터(1번 되돌림이 원천을 다시 만든다).

---

## 4. 채점 기준

스크립트가 아래 조건을 기계적으로 본다. 근거는 실행 원문에서 `===== … (단계 이름)` 으로 찾는다. 회차 합격 = 불합격 0 · 미시행 0.

| 시험 | 합격 조건 | 단계 |
|---|---|---|
| T-L01~04, T-L07, T-L10, T-L11, T-L15 | L 단계 `verify` 종료 코드 0, `불일치 0` (건수·합계·행 해시, KIS normalization 규칙). T-L10 은 보고서 `비유니코드` 주의, T-L11 의 인덱스 근접 경고는 시험 DB 에 해당 컬럼이 없어 `SchemaPlannerTest` 로 본다 | l_verify |
| T-L08 | 위 + 보고서 `trailing_space: keep` 주의 | plan |
| T-L14 | 위 + 보고서 `센티널 날짜 행` | plan |
| T-L09 | NUL 규칙을 뺀 계획이 종료 코드 3 + `NUL`, 그 설정의 `load` 가 종료 코드 5 + `NUL`, 규칙대로면 위 검증 일치 | plan_nulfail, l_load_nulfail |
| T-L16 | kill -9 뒤 `load` 종료 코드 0 + `건너뜀`, 검증 일치 | l_load_kill, l_load_resume |
| T-L05·T-L06 | `check` 종료 코드 0(L·C 두 번), `app_user.user_id`·`seq_doc_no` 의 `대상 다음 값 N = 원천 …` | l_check, c_check |
| T-L12 | `check --probe` 의 `uq_app_user_login: 대소문자만 다른 … 입력이 막혔다`(L·C 두 번) | l_check, c_check |
| T-L13 | `check` 의 `rating_rank`·`file_ext` `GENERATED … STORED` + 검증 일치 | l_check |
| T-L17 | 오프라인 빌드 종료 코드 0, `Failures: 0, Errors: 0`, `NormalizerTest` 실행 | build |
| T-C01, T-C03~07, T-C12 | C 단계 `cutover` 종료 코드 0, `불일치 0`. 쓰기 스크립트(`30_writes.sql`)가 입력 후 삭제·트리거 이력·LOB 미변경 UPDATE·PK 변경·긴 트랜잭션을 섞어 넣는다 | c_cutover |
| T-C02 | 워터마크 없는 `load` 종료 코드 4 + `워터마크가 없다` | c_load_nowm |
| T-C08 | 다시 띄운 `sync` 두 번 모두 `이어 받는다` + 위 검증 일치 | c_sync2, c_sync3 |
| T-C09 | `sync` 에 `로그를 읽지 않음` 경고가 나오고, 마지막 진행 줄 지연 < 15초(따라잡음) | c_sync3 |
| T-C10 | 정리 뒤 `sync` 종료 코드 5 + `보존 기간`, 이어 `reset` 종료 코드 0 | c_sync_tc10 |
| T-C11 | `cutover` 일치 + `소요 시간(예상 다운타임)` + `check --probe` 종료 코드 0 과 `fk_rating_issuer: 부모에 없는 … 막혔다` | c_cutover, c_check |
| T-N01 | 시작·끝 모두 인터넷 차단 + L·C 전환 종료 코드 0. 인터넷이 연결돼 있으면 미시행 | 0. 인터넷 차단 확인 |
| T-N02 | jar 안 `templates`·`static` 에 외부 URL 을 읽는 `src`·`href`·`url()`·`@import` 0 | 2. jar 안 화면 파일 |
| T-N03 | 기록된 `kdms.jar` 연결 상대가 `.env` 의 원천·대상(그리고 127.0.0.1) 뿐 | 채점 끝 `외부 연결 감시` 줄 |

---

## 5. 결과 보내기

```bash
git add runs/*_p6_r1_*.txt runs/*_p6_r2_*.txt          # 파일 이름을 지정한다(git add . 금지)
git status                                             # .env·config/kdms.yml·docs 의 다른 파일이 없는지
git commit -m "6단계 리허설 1·2회차 결과"
git push
```

또는 두 채점표(`_score.txt`) 내용을 채팅에 붙인다. 불합격이 있으면 같은 회차의 `_log.txt` 도.

노트북 쪽 결과(`runs\…_p6_*.txt`)는 노트북에만 있다. 불합격 시험이 노트북 단계(되돌림·쓰기·T-C10·캡처 Job)와 관련 있으면 그 파일 내용도 보낸다.

---

## 6. 막히면

| 증상 | 할 일 |
|---|---|
| 1. 빌드 `Cannot access central … in offline mode` | 인터넷을 되돌리고 §2 의 `./mvnw -B clean package -DskipTests` 한 번 → 다시 끊고 처음부터 |
| `워터마크 기록이 120초 안에 나오지 않음` | 노트북 SQL Agent·CDC(1번 되돌림 결과 파일). `out/rehearsal/<시각>_r<회차>/c_sync1.txt` |
| T-C10 의 `sync` 가 120초 뒤 강제 종료됨 | 3번 `tc10` 이 실패했거나 Enter 를 먼저 눌렀다. 노트북 `runs\…_p6_tc10.txt` |
| T-C09 불합격(경고가 안 나옴) | 5번을 Enter 전에 실행하지 않았다. 다음 회차에서 순서대로 |
| 그 밖 | [runbook.md](runbook.md) §9, [test-env.md](test-env.md) §7 |
