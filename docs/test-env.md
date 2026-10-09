# 시험 환경 준비와 1단계 확인 (0단계 + 1단계 완료 기준)

> 상태: 진행 중 · 최종 갱신: 2026-10-09 · a43a5f8 · 근거: PR #2·#3·#5·#4·#8·#9·#10 에서 단계마다 절 추가, WORKLOG Mac 항목

[plan.md](plan.md) §2.1(0단계)과 §6 1단계 완료 기준을 실제로 해 보는 순서다. 사람이 한 번 한다.

```
Mac (개발, kdms.jar 실행)  ───── 같은 LAN ─────>  Windows 노트북 192.168.0.12
                                                   ├ SQL Server 2019 Developer :1433 (로그인 kodong_ms), SQL Agent
                                                   └ PostgreSQL 16 (Docker 컨테이너 mig-pg) :5432
```

- 비밀번호는 `.env` 에만 둔다. 이 문서의 명령은 비밀번호를 명령줄에 싣지 않는다.
- 실행 결과는 `runs/YYYYMMDD_HHMM_<단계>.txt` 로 남기고 [WORKLOG.md](../WORKLOG.md) 에 요약한다.
- KIS 저장소는 읽기 전용이다. 아래 어느 명령도 KIS 의 파일·DB(`MIG_*`, `mig`)를 바꾸지 않는다(`MIG_MOCK` 은 백업만 읽는다).

---

## 1. 노트북: KDMS 저장소 받기 (PowerShell 7)

시험 준비 SQL 이 KDMS 저장소에 있으므로 노트북에도 받는다(읽기용. 노트북에서 커밋하지 않아도 된다).

```powershell
cd C:\                                   # 원하는 상위 폴더
git clone https://github.com/kodong75/KDMS.git
cd KDMS
git config core.autocrlf false
```

이후 노트북 명령은 모두 `KDMS` 폴더에서 PowerShell 7(`pwsh`)로 한다. 관리자 권한이 필요한 것은 [관리자] 로 표시했다.

## 2. 노트북: 원천 MS-SQL 준비

| 순서 | 명령 | 하는 일 | 확인 |
|---|---|---|---|
| ① [관리자] | `Start-Service SQLSERVERAGENT` | CDC 캡처 Job 이 돌도록 Agent 시작 | `Get-Service SQLSERVERAGENT` → Running |
| ② | `./scripts/Invoke-KdmsSql.ps1 -File test/sql/mssql/00_restore_kdms_mock.sql -Stage p0_restore -Var @{ REPLACE = '0' }` | `MIG_MOCK` 을 백업(COPY_ONLY)해 `KDMS_MOCK` 으로 복원, 스냅숏 격리 켜기 | 결과 파일 마지막 표에서 `mig_mock_rows` = `kdms_mock_rows` |
| ③ | `./scripts/Invoke-KdmsSql.ps1 -File test/sql/mssql/10_enable_cdc.sql -Stage p0_cdc` | `KDMS_MOCK` CDC 켜기, PK 있는 dbo 테이블 전부 캡처(게이팅 역할 `kdms_cdc_reader`) | `is_cdc_enabled = 1`, `cdc.change_tables` 에 PK 있는 테이블 수만큼, `sp_cdc_help_jobs` 에 capture·cleanup |
| ④ | `./scripts/Invoke-KdmsSql.ps1 -File test/sql/mssql/20_grant_kdms_login.sql -Stage p0_grant -Var @{ KDMS_LOGIN = 'kodong_ms' }` | `kodong_ms` 에 읽기·CDC 읽기 권한 | 마지막 `sp_cdc_help_change_data_capture` 결과가 비어 있지 않음 |

- `Invoke-KdmsSql.ps1` 는 Windows 인증(관리자 계정의 `-E`)으로 붙는다. 노트북 로그인 사용자가 SQL Server sysadmin 이어야 한다(KIS 설치 때와 같음).
- 시험 데이터를 처음 상태로 되돌리려면 ② 를 `REPLACE = '1'` 로 다시 실행하고 ③·④ 를 다시 한다. 다 지우려면 `test/sql/mssql/90_cleanup.sql`.
- 원천 로그인 권한은 ④ 스크립트 그대로 4단계 CDC(스트리밍·재시작·drain)까지 된다(2026-10-08 클라우드 실측, [cdc.md](cdc.md) §7). 서버 수준 `VIEW SERVER STATE` 는 KIS `00_login_mig.sql` 이 이미 준다.

## 3. 노트북: 대상 PG 준비

KIS 의 PG 컨테이너(`mig-pg`)에 새 DB `kdms` 와 전용 역할 `kdms_app` 을 만든다. KIS 의 `mig` DB 와는 분리된다.

```powershell
$env:KDMS_TGT_PASSWORD = Read-Host 'kdms_app 암호(새로 정함)' -MaskInput    # 화면·기록에 남지 않게 입력
Get-Content -Raw test/sql/pg/00_create_kdms_db.sql |
    docker exec -i -e KDMS_TGT_PASSWORD mig-pg psql -U postgres -d postgres -v ON_ERROR_STOP=1 |
    Set-Content -Encoding utf8 ("runs/{0}_p0_pg.txt" -f (Get-Date -Format 'yyyyMMdd_HHmm'))
Remove-Item Env:KDMS_TGT_PASSWORD
```

- 같은 암호를 Mac 의 `.env` `KDMS_TGT_PASSWORD` 에 적는다.
- Mac 에서 5432 로 붙으려면 KIS `.env` 의 `PG_BIND=0.0.0.0` 과 방화벽 규칙(개인 프로필·로컬 서브넷만)이 필요하다(KIS:docs/pg-lan-access.md). 1433 도 같은 방식으로 열려 있어야 한다.

## 4. Mac: 준비물 설치 (터미널, 한 번만)

```bash
brew install openjdk@21 git          # Java 21 (빌드·실행), git. Maven 은 저장소의 ./mvnw 가 알아서 받는다
java -version                        # "21" 이 보이면 된다. 안 보이면 brew 가 안내하는 PATH 설정 줄을 실행
cd ~ && git clone https://github.com/kodong75/KDMS.git   # 이미 ~/KDMS 가 있으면 생략
nc -vz 192.168.0.12 1433             # succeeded 면 MS-SQL 포트 열림
nc -vz 192.168.0.12 5432             # succeeded 면 PG 포트 열림
```

## 5. Mac: 설정 파일

```bash
cd ~/KDMS
cp .env.example .env                 # 비밀번호 칸 두 개(KDMS_SRC_PASSWORD, KDMS_TGT_PASSWORD)를 채운다
cp config/kdms.example.yml config/kdms.yml
open -e .env                         # 텍스트 편집기로 열기
```

`.env` 와 `config/kdms.yml` 은 `.gitignore` 에 있어 커밋되지 않는다.

## 6. Mac: 1단계 완료 기준 확인

브랜치를 아직 머지하지 않았으면 먼저 `git checkout <브랜치>` 로 PR 브랜치를 받는다. 각 명령의 출력은 `runs/` 에 남긴다.

```bash
cd ~/KDMS
S=$(date +%Y%m%d_%H%M)

# ① 온라인 빌드 1회(의존성·플러그인을 ~/.m2 에 받는다) + 단위 시험·라이선스 검사
#    clean 을 꼭 넣는다. 빼면 clean 플러그인을 받지 않아 ② 오프라인 빌드가 PluginResolutionException 으로 실패한다
./mvnw -B clean package 2>&1 | tee runs/${S}_p1_build_online.txt

# ② 오프라인 빌드(-o): 외부 다운로드 없이 다시 빌드되는지
./mvnw -B -o clean package 2>&1 | tee runs/${S}_p1_build_offline.txt

# ③ 인터넷을 끊고(공유기의 인터넷(WAN) 선만 뽑아 LAN 은 살린다) 두 DB 버전 출력
#    파이프(| tee) 뒤의 $? 는 tee 의 종료 코드라 늘 0 이다. 파일로 받은 뒤 종료 코드를 보고 내용을 출력한다
java -jar target/kdms.jar status > runs/${S}_p1_status.txt 2>&1; echo "exit=$?"; cat runs/${S}_p1_status.txt

# ④ 관리 스키마 만들기(두 번 해도 같다) 후 다시 status
java -jar target/kdms.jar init 2>&1 | tee runs/${S}_p1_init.txt
java -jar target/kdms.jar status 2>&1 | tee -a runs/${S}_p1_init.txt

# ⑤ 통합 시험(노트북 DB 에 붙는 시험만)
./mvnw -B -o test -Pintegration 2>&1 | tee runs/${S}_p1_integration.txt

# ⑥ 웹 화면: 브라우저로 http://127.0.0.1:8080 , 끝낼 때 Ctrl+C
java -jar target/kdms.jar web
```

| 기준(plan.md §6 1단계) | 어디서 보나 |
|---|---|
| `mvn -o package` 성공 | ② 마지막 줄 `BUILD SUCCESS` |
| 네트워크를 끊고 `status` 가 두 DB 버전 출력 | ③ `[원천] 버전 Microsoft SQL Server 2019 …`, `[대상] 버전 PostgreSQL 16.…`, 종료 코드 0(`echo $?`) |
| 라이선스 보고서 미확인 0 | ① 의 `license:…add-third-party` 부분에 오류 없음. 목록은 jar 안 `META-INF/THIRD-PARTY.txt` (`unzip -p target/kdms.jar META-INF/THIRD-PARTY.txt`), SBOM 은 `META-INF/sbom/application.cdx.json` |

`status` 종료 코드: 0 둘 다 접속, 1 설정 오류, 2 접속 실패. 접속은 됐지만 고칠 것(CDC 꺼짐, Agent 멈춤, superuser 접속 등)은 `주의` 줄로 나온다.

## 8. Mac: 2단계(스키마 변환) 확인

§1~§6 을 아직 안 했으면 먼저 한다(원천 `KDMS_MOCK`·대상 `kdms` 준비, `.env`, `config/kdms.yml`).
1단계 때 `config/kdms.yml` 을 복사했다면 `rules:` 줄이 `""` 이다. 2단계부터는 저장소의 KDMS_MOCK 규칙(계산 컬럼 PG 식)을 쓰므로 고친다.

```bash
cd ~/KDMS
git fetch origin && git checkout claude/project-thread-9nq4o2   # PR 브랜치(머지 뒤에는 git checkout main && git pull)
grep '^rules' config/kdms.yml        # rules: config/kdms-rules.yml 이어야 한다. 아니면 그 줄을 이렇게 고친다
S=$(date +%Y%m%d_%H%M)

# ① 빌드 + 단위 시험
./mvnw -B package 2>&1 | tee runs/${S}_p2_build.txt

# ② 계획(원천 읽기만, 대상은 안 건드림). 마지막 줄 근처 "결과: 통과" 를 본다
java -jar target/kdms.jar plan 2>&1 | tee runs/${S}_p2_plan.txt

# ③ 데이터 검사까지. KIS 가 심은 NUL 때문에 "결과: 막힘", 오류 1건(dbo.issuer.issuer_nm)이 정상(T-L09)
java -jar target/kdms.jar plan --scan -o out/kdms_mock_scan 2>&1 | tee runs/${S}_p2_plan_scan.txt

# ④ 통합 시험: 원천 카탈로그 = 시험 고정값(SourceCatalogIT), 대상 DDL 적용(TargetDdlIT, 시험 스키마 kdms_it_ddl 만 쓰고 지움)
./mvnw -B -o test -Pintegration -Dtest='SourceCatalogIT,TargetDdlIT' 2>&1 | tee runs/${S}_p2_integration.txt

# ⑤ 대상 kdms DB 에 테이블 만들기(dbo 스키마). 다시 하려면 --replace
java -jar target/kdms.jar schema 2>&1 | tee runs/${S}_p2_schema.txt
```

| 기준(plan.md §6 2단계) | 어디서 보나 |
|---|---|
| `KDMS_MOCK` 대상 DDL 이 KIS mock.sql 과 같은 타입 | ④ `SourceCatalogIT`·`TargetDdlIT` 통과(`Tests run: 5, Failures: 0`). 차이의 이유는 [schema-conversion.md](schema-conversion.md) §4 |
| 보고서 | ② `runs/…_p2_plan.txt`: 오류 0 · 경고 9 · 주의 3 |
| 대상 적용 | ⑤ `적용: 문장 …개, 테이블 7개`, `상태 SCHEMA_DONE` |

`SourceCatalogIT` 가 실패하면 실패 메시지(어느 테이블·컬럼 값이 다른지)를 그대로 보내 준다. 클라우드에서 원천 MS-SQL 을 확인하지 못했기 때문에 카탈로그 조회 SQL 이나 시험 고정값을 고친다.

## 9. Mac: 3단계(전체 적재·검증) 확인

§8 ⑤ `schema` 까지 끝난 상태에서 한다. 원천 `KDMS_MOCK` 은 `ALLOW_SNAPSHOT_ISOLATION ON` 이어야 한다(§2 의 `00_restore_kdms_mock.sql` 이 켠다). 이 단계는 원천에 쓰기가 없어야 한다.
`config/kdms.yml` 에 `load:` 절이 없으면 기본값(테이블 4개 병렬 × 구간 2개, snapshot)으로 돈다.

```bash
cd /Users/kodong/Projects/KDMS
git fetch origin && git checkout claude/project-thread-2stohv   # PR 브랜치(머지 뒤에는 git checkout main && git pull)
S=$(date +%Y%m%d_%H%M)

# ① 빌드 + 단위 시험
./mvnw -B package 2>&1 | tee runs/${S}_p3_build.txt

# ② 전체 적재. 마지막 "결과: 테이블 7개 중 적재 7개 … 실패 0개" (대상 행 수는 노트북 원천 건수와 같으면 된다. 2026-10-08 노트북 48,053) 과 "적재 뒤 DDL" 줄을 본다
java -jar target/kdms.jar load 2>&1 | tee runs/${S}_p3_load.txt

# ③ 검증. "결과: 검증 항목 30개 중 일치 30 · 불일치 0" 이 기준. 종료 코드 0
java -jar target/kdms.jar verify 2>&1 | tee runs/${S}_p3_verify.txt; echo "종료 코드 ${pipestatus[1]}" | tee -a runs/${S}_p3_verify.txt
# 위 echo 의 pipestatus 는 Mac 기본 셸 zsh 용이다(bash 라면 ${PIPESTATUS[0]})

# ④ 중단·재시작: 처음부터 천천히 적재하다가 8초 뒤 강제 종료 → 다시 실행 → 검증
java -jar target/kdms.jar load --reset --throttle-ms 1000 > runs/${S}_p3_kill.txt 2>&1 &
sleep 8; kill -9 $!; echo "강제 종료" >> runs/${S}_p3_kill.txt
java -jar target/kdms.jar load 2>&1 | tee -a runs/${S}_p3_kill.txt          # "이미 적재돼 건너뜀 N" 과 남은 구간만 적재
java -jar target/kdms.jar verify 2>&1 | tee -a runs/${S}_p3_kill.txt        # 다시 30/30

# ⑤ 통합 시험: LoadVerifyIT(대상 시험 스키마 kdms_it_load 만 쓰고 지움) + 2단계 것
./mvnw -B -o test -Pintegration -Dtest='LoadVerifyIT,SourceCatalogIT,TargetDdlIT' 2>&1 | tee runs/${S}_p3_integration.txt
```

| 기준(plan.md §6 3단계) | 어디서 보나 |
|---|---|
| 쓰기 없는 상태에서 MVP 테이블 전부 검증 일치 | ③ `검증 항목 30개 중 일치 30 · 불일치 0`, 종료 코드 0 |
| 적재 도중 죽였다 다시 실행해 이어서 끝나고 검증 일치 | ④ 두 번째 `load` 의 `이미 적재돼 건너뜀` 이 0 보다 크고 `실패 0개`, 마지막 `verify` 30/30 |
| 통합 시험 | ⑤ `Tests run: …, Failures: 0, Errors: 0` |

④ 에서 8초 안에 적재가 다 끝나면(건너뜀 7) `sleep 8` 을 `sleep 4` 로 줄여 다시 한다.
③ 이 불일치면 보고서의 `차이 행` 줄(PK 만 나온다)과 `runs/…_p3_verify.txt` 를 그대로 보내 준다. 클라우드에서 노트북 원천을 확인하지 못했기 때문이다.

## 10. Mac + 노트북: 4단계(변경분 수집·반영) 확인

§9 까지 끝난 상태에서 한다. 원천에 **쓰기를 넣는 동안** 적재·반영하고, 쓰기를 멈춘 뒤 검증이 일치하는지 본다. 명령의 뜻은 [cdc.md](cdc.md) §1.
창이 셋 필요하다: Mac 터미널 A(`kdms sync`), Mac 터미널 B(적재·검증), 노트북 PowerShell 7(원천 쓰기).

**준비(노트북 PowerShell 7)**

```powershell
cd C:\Projects\KDMS
git fetch origin; git checkout claude/stage4-cdc-fxm28p     # 쓰기 스크립트 test/sql/mssql/30_writes.sql 을 받는다(머지 뒤에는 git checkout main; git pull)
Get-Service SQLSERVERAGENT                                  # Running 이어야 한다. 아니면 [관리자] Start-Service SQLSERVERAGENT (재부팅 뒤 매번)
```

**① 터미널 A (Mac): 빌드, 3단계 데이터 지우기, 동기화 시작**

```bash
cd /Users/kodong/Projects/KDMS
git fetch origin && git checkout claude/stage4-cdc-fxm28p   # PR 브랜치(머지 뒤에는 git checkout main && git pull)
S=$(date +%Y%m%d_%H%M); echo $S                             # 이 값을 터미널 B 에서도 쓴다(화면에 나온 값을 적어 둔다)
./mvnw -B package 2>&1 | tee runs/${S}_p4_build.txt          # 빌드 + 단위 시험. 끝에 BUILD SUCCESS
java -jar target/kdms.jar reset --yes 2>&1 | tee runs/${S}_p4_reset.txt   # 3단계에서 적재한 대상 테이블을 비우고 작업을 적재 전으로
java -jar target/kdms.jar sync 2>&1 | tee runs/${S}_p4_sync1.txt          # 띄워 둔다. "워터마크 기록: …" 줄이 나올 때까지 기다린다
```

**② 노트북: 원천 쓰기 5분** (① 의 "워터마크 기록" 이 나온 뒤)

```powershell
./scripts/Invoke-KdmsSql.ps1 -File test/sql/mssql/30_writes.sql -Stage p4_writes -Var @{ DURATION_SEC = '300' }
# 끝나면 "결과: runs\…_p4_writes.txt (exit 0, 300…s)". 이 파일의 writes_stopped_at 이 쓰기 중지 시각이다
```

**③ 터미널 B (Mac): 쓰기 도중 적재, 동기화 강제 종료** (② 를 시작하고 20초쯤 뒤)

```bash
cd /Users/kodong/Projects/KDMS
S=여기에_①의_값                                               # 예: S=20261009_1030
java -jar target/kdms.jar load --throttle-ms 2000 2>&1 | tee runs/${S}_p4_load.txt
# 첫 줄 근처 "워터마크 … 뒤 시점을 적재한다", 끝에 "실패 0개" 와 "적재 뒤 DDL(UNIQUE·인덱스)은 변경분 반영 중이라 지금 적용하지 않는다"
pkill -9 -f 'kdms.jar sync'                                  # 터미널 A 의 sync 를 강제 종료(T-C08). A 에 "killed" 가 보인다
```

**④ 터미널 A: 동기화 다시 시작** (③ 의 강제 종료 직후)

```bash
java -jar target/kdms.jar sync 2>&1 | tee runs/${S}_p4_sync2.txt
# "저장된 오프셋 다음부터 이어 받는다" 뒤 10초마다 "[시:분:초] 수집 … · 반영 … · 대기 0 · 지연 …초"
```

**⑤ 터미널 A: 쓰기가 끝난 뒤(② 의 결과 줄이 나온 뒤) 마무리**

```bash
# 먼저 Ctrl+C 로 ④ 를 멈춘다("중지 요청: 진행 중 배치를 마치고 멈춘다")
java -jar target/kdms.jar sync --drain 2>&1 | tee runs/${S}_p4_drain.txt; echo "종료 코드 ${pipestatus[1]}" | tee -a runs/${S}_p4_drain.txt
# "따라잡음(--drain): … 반영 대기 0건" 과 종료 코드 0
java -jar target/kdms.jar verify 2>&1 | tee runs/${S}_p4_verify.txt; echo "종료 코드 ${pipestatus[1]}" | tee -a runs/${S}_p4_verify.txt
# "결과: 검증 항목 30개 중 일치 30 · 불일치 0" 과 종료 코드 0
java -jar target/kdms.jar schema --phase post-load 2>&1 | tee runs/${S}_p4_postload.txt   # 반영 중 미뤄 둔 UNIQUE·인덱스
```

`${pipestatus[1]}` 은 Mac 기본 셸 zsh 용이다(bash 라면 `${PIPESTATUS[0]}`). `tee` 를 거치면 `$?` 는 늘 0 이라 이렇게 본다.

| 기준(plan.md §6 4단계) | 어디서 보나 |
|---|---|
| 쓰기를 넣는 동안 적재 → 반영 | ③ `load` 가 `실패 0개`, ④ 의 진행 줄에서 `반영` 이 늘고 `대기` 가 0 근처 |
| 쓰기 중지 후 검증 일치 | ⑤ `verify` 30/30, 종료 코드 0 |
| 강제 종료 뒤 이어 받기(T-C08) | ④ 가 오류 없이 시작하고 ⑤ 가 일치 |

- ③ 의 `load` 가 ② 보다 먼저 끝나도 된다(쓰기는 계속 반영된다). 적재가 너무 빨리 끝나 쓰기와 겹치지 않으면 `--throttle-ms` 를 5000 으로 올려 처음부터(`reset --yes` → ①) 다시 한다.
- ⑤ `--drain` 을 쓰기 도중에 시작하면 "원천 쓰기가 아직 있다" 를 찍고 쓰기가 끝날 때까지 기다린다(정상).
- ⑤ 가 불일치면 `runs/${S}_p4_*.txt` 전부와 노트북 `runs\…_p4_writes.txt` 를 보내 준다.
- `30_writes.sql` 은 `KDMS_MOCK` 데이터를 바꾼다(KIS 가 심은 NUL 행 이름 변경, rating_id 5 삭제 등). 그 뒤에는 §8 ④·§9 ⑤ 통합 시험(SourceCatalogIT·LoadVerifyIT)이 고정값과 달라 실패한다. 다시 하려면 노트북 §2 ② 를 `REPLACE = '1'` 로 → ③ → ④ 로 되돌리고, Mac 에서 `schema --replace` 부터 한다.

## 11. Mac + 노트북: 5단계(전환·화면) 확인

§10 까지 끝난 상태에서 한다. 명령의 뜻은 [cutover.md](cutover.md). 창은 넷: Mac 터미널 A(`kdms sync`), B(적재·전환), C(웹 화면), 노트북 PowerShell 7(원천 쓰기).
4단계 쓰기 시험으로 `KDMS_MOCK` 이 바뀌어 있으므로 먼저 되돌린다.

**준비(노트북 PowerShell 7)**: §2 ② 를 `REPLACE = '1'` 로, 이어서 ③·④

```powershell
cd C:\Projects\KDMS
git fetch origin; git checkout claude/stage5-cutover-jdq5mm      # 머지 뒤에는 git checkout main; git pull
Get-Service SQLSERVERAGENT                                     # Running (아니면 [관리자] Start-Service SQLSERVERAGENT)
./scripts/Invoke-KdmsSql.ps1 -File test/sql/mssql/00_restore_kdms_mock.sql -Stage p5_restore -Var @{ REPLACE = '1' }
./scripts/Invoke-KdmsSql.ps1 -File test/sql/mssql/10_enable_cdc.sql -Stage p5_cdc
./scripts/Invoke-KdmsSql.ps1 -File test/sql/mssql/20_grant_kdms_login.sql -Stage p5_grant -Var @{ KDMS_LOGIN = 'kodong_ms' }
```

**① 터미널 B (Mac): 빌드, 처음부터**

```bash
cd /Users/kodong/Projects/KDMS
git fetch origin && git checkout claude/stage5-cutover-jdq5mm
S=$(date +%Y%m%d_%H%M); echo $S                                    # 다른 창에서도 이 값을 쓴다
./mvnw -B package 2>&1 | tee runs/${S}_p5_build.txt                 # BUILD SUCCESS
java -jar target/kdms.jar reset --yes 2>&1 | tee runs/${S}_p5_reset.txt
java -jar target/kdms.jar schema --replace 2>&1 | tee runs/${S}_p5_schema.txt   # 4단계에서 만든 UNIQUE·인덱스까지 지우고 다시 만든다
```

**② 터미널 C: 웹 화면** (브라우저로 http://127.0.0.1:8080 을 열어 두고 아래 단계마다 본다)

```bash
cd /Users/kodong/Projects/KDMS && java -jar target/kdms.jar web
```

**③ 터미널 A: 동기화** → "워터마크 기록" 이 나오면 노트북에서 쓰기 5분, 20초쯤 뒤 터미널 B 에서 적재

```bash
cd /Users/kodong/Projects/KDMS; S=여기에_①의_값
java -jar target/kdms.jar sync 2>&1 | tee runs/${S}_p5_sync.txt
# 적재 전 진행 줄의 지연이 0 근처여야 한다(4단계에서는 348초처럼 크게 보였다). 대기 괄호에 "적재 전 테이블 7개, 그 변경 N"
```
```powershell
./scripts/Invoke-KdmsSql.ps1 -File test/sql/mssql/30_writes.sql -Stage p5_writes -Var @{ DURATION_SEC = '300' }
```
```bash
java -jar target/kdms.jar load --throttle-ms 2000 2>&1 | tee runs/${S}_p5_load.txt     # 터미널 B. 끝에 "실패 0개"
```

**④ 쓰기가 끝난 뒤(노트북 결과 줄) 전환: S1**

```bash
# 터미널 A: Ctrl+C ("중지 요청: 진행 중 배치를 마치고 멈춘다")
# 터미널 B:
java -jar target/kdms.jar cutover --yes 2>&1 | tee runs/${S}_p5_cutover.txt; echo "종료 코드 ${pipestatus[1]}" | tee -a runs/${S}_p5_cutover.txt
# [1/6] ~ [6/6] 이 모두 완료·건너뜀, "검증 항목 30개 중 일치 30", 끝에 "소요 시간(예상 다운타임) N초", 종료 코드 0
java -jar target/kdms.jar status 2>&1 | tee runs/${S}_p5_status.txt      # [작업] 상태 DONE, 전환 cutover N: DONE
```

**⑤ 전환 뒤 새 입력: S4** (노트북 PowerShell 7. PG 컨테이너 안 psql 로 kdms_app 으로 붙는다. 입력은 ROLLBACK 한다)

```powershell
@'
BEGIN;
INSERT INTO dbo.app_user (login_id, user_nm) VALUES ('kdms_s4_new', 'S4') RETURNING user_id;
SELECT nextval('dbo.seq_doc_no');
INSERT INTO dbo.rating (issuer_id, rating_cd, rating_dt, eff_dtm, issue_amt, coupon_rate, is_watch) VALUES (-1, 'AAA', '2026-10-08', now(), 1, 1, false);
ROLLBACK;
'@ | docker exec -i mig-pg psql -U kdms_app -d kdms 2>&1 | Tee-Object runs\p5_s4.txt
```

- `user_id` 가 ④ 출력의 `IDENTITY "dbo"."app_user"."user_id": 원천 N → 대상 다음 값 N+1` 의 N+1, `nextval` 이 `SEQUENCE "dbo"."seq_doc_no"` 의 다음 값과 같아야 한다.
- 마지막 INSERT 는 `violates foreign key constraint` 오류여야 한다(FK 가 켜졌다). 결과 파일 `runs\p5_s4.txt` 내용을 채팅에 붙여 준다.

| 기준(plan.md §6 5단계, cutover.md §6) | 어디서 보나 |
|---|---|
| S1 정상 전환, 전환 소요 시간 | ④ 종료 코드 0, 30/30, `소요 시간(예상 다운타임)` |
| S4 전환 뒤 새 입력 | ⑤ 새 id 가 원천 `IDENT_CURRENT + 1`, FK 오류 |
| 화면(진행률·지연·검증) | ② 화면이 단계마다 바뀌고 끝에 "완료"·검증 30/30·전환 단계 표 |
| S2·S3 | 클라우드에서 확인했다(WORKLOG 5단계). 노트북에서도 하려면 cutover.md §6 순서대로 |

- ④ 의 `[1/6] 마지막 반영` 이 "원천 쓰기가 아직 있다" 를 찍고 끝나지 않으면 노트북 쓰기가 아직 돌고 있는 것이다. 기본 600초 뒤 종료 코드 6 으로 멈춘다(작업 FAILED). 쓰기가 끝난 뒤 같은 명령을 다시 한다.
- ④ 가 종료 코드 5(검증 불일치)면 `runs/${S}_p5_*.txt` 전부와 노트북 `runs\…_p5_writes.txt` 를 보내 준다.
- 웹 화면 버튼으로 해 보려면 ① 부터 다시 한 뒤 ③ 의 `sync`·`load` 대신 화면의 "동기화 시작"·"전체 적재", ④ 대신 "전환 시작" 을 누른다(화면에서 띄운 동기화는 전환이 먼저 멈춘다).

## 7. 자주 막히는 곳

| 증상 | 원인 → 해결 |
|---|---|
| `설정 오류: … 환경 변수 KDMS_SRC_PASSWORD 가 없습니다` | `.env` 를 저장소 루트에 두지 않았거나 이름이 다르다. `--env-file 경로` 로 지정할 수도 있다 |
| 원천 `The TCP/IP connection … has failed` | 1433 방화벽·SQL Server TCP 설정. Mac `nc -vz 192.168.0.12 1433` 부터 |
| 원천 `PKIX path building failed` | 노트북 자체 서명 인증서. `.env` `KDMS_SRC_TRUST_CERT=true`(시험 환경만) |
| 원천 `Login failed for user 'kodong_ms'` | 노트북 SQL Server 오류 로그의 원인 문구로 가른다(표 아래 명령). 흔한 것은 `.env` `KDMS_SRC_PASSWORD` 불일치, 혼합 인증 꺼짐, `KDMS_MOCK` 접근 권한 없음. `.env` 값은 따옴표 없이 쓴다(텍스트 편집기가 `"` 를 둥근 따옴표로 바꾸면 따옴표까지 암호가 된다) |
| 대상 `password authentication failed for user "kdms_app"` | §3 에서 정한 암호와 `.env` `KDMS_TGT_PASSWORD` 가 다르다. §3 을 다시 실행하면 암호를 다시 맞춘다 |
| `load` 가 `Snapshot isolation transaction failed … 3952` | 원천 DB 에 스냅샷 격리가 꺼져 있다. 노트북에서 `ALTER DATABASE KDMS_MOCK SET ALLOW_SNAPSHOT_ISOLATION ON;`(§2 의 00 스크립트) |
| `load` 가 `다른 kdms load 가 작업 … 을 적재하고 있다` | 다른 터미널의 `load` 가 아직 돈다(§9 ④ 의 백그라운드 포함). `pgrep -fl kdms.jar` 로 확인 |
| `load` 가 issuer 에서 `NUL 문자` 로 실패 | `config/kdms.yml` 의 `rules:` 가 `config/kdms-rules.yml` 이 아니다(load-verify.md §4) |
| `plan` 이 `결과: 막힘` · `계산 컬럼 식을 PG 로 옮겨야 한다` | `config/kdms.yml` 의 `rules:` 가 비어 있다. `rules: config/kdms-rules.yml` |
| `schema` 가 `적용하지 않음: 대상에 이미 있다` | 이미 만든 테이블이다. 다시 만들려면 `--replace`(안의 데이터도 지워진다) |
| `SQL Server Agent 가 실행 중이 아니다` 인데 `Get-Service SQLSERVERAGENT` 는 Running | 한국어 Windows 는 서비스 이름이 'SQL Server 에이전트'라 옛 검사가 못 찾았다. 2026-10-01 에 실행 파일 이름(SQLAGENT)으로 찾도록 고쳤다. 저장소를 `git pull` 한 뒤 다시 실행 |
| ② 오프라인 빌드 `PluginResolutionException` | ① 을 `clean` 없이 돌려 clean 플러그인이 `~/.m2` 에 없다. 인터넷이 될 때 `./mvnw -B clean package` 한 번 → ② 다시 |
| `sync` 가 `원천 CDC 캡처 인스턴스가 없는 테이블` | §2 ③ 이 안 됐거나 `kodong_ms` 에 `cdc` 읽기 권한이 없다(§2 ④). 노트북에서 ③·④ 다시 |
| `sync` 가 "워터마크 기록" 을 안 찍고 멈춘 듯함 | SQL Agent 가 꺼져 있으면 CDC 가 안 쌓여도 엔진은 뜬다. 노트북 `Get-Service SQLSERVERAGENT` → Running. 그래도 1분 넘게 없으면 `runs/…_p4_sync1.txt` 를 보내 준다 |
| `load` 가 `워터마크가 없다` | 터미널 A 의 `sync` 가 "워터마크 기록" 을 찍기 전에 적재했다. 기다렸다 다시. 원천 쓰기가 없는 3단계 방식이면 `--no-cdc` |
| `sync` 가 `보존 기간이 지나 …(T-C10)` | 동기화를 멈춘 채 CDC 보존 기간(기본 3일)이 지났다. `reset --yes` → §10 ① 부터 |
| `sync` 가 `워터마크는 있는데 Debezium 오프셋이 비어 있다` | 관리 표를 손으로 지웠거나 `kdms.debezium_offset_<번호>` 가 없어졌다. `reset --yes` → §10 ① 부터 |
| `reset` 이 `kdms sync 이 작업 … 을 실행하고 있다` | 다른 터미널의 `sync` 를 Ctrl+C 로 멈춘다(`pgrep -fl kdms.jar`) |
| `nc` 는 되는데 Java 만 안 됨 | macOS 로컬 네트워크 권한: 시스템 설정 → 개인정보 보호 및 보안 → 로컬 네트워크 → 터미널 켜기 |

원천 로그인 실패 원인 보기(노트북 PowerShell 7, Windows 인증):

```powershell
sqlcmd -E -f 65001 -W -Q "SET NOCOUNT ON; SELECT SERVERPROPERTY('IsIntegratedSecurityOnly') AS windows_only; EXEC xp_readerrorlog 0, 1, N'kodong_ms';" -o ("runs/{0}_p1_loginfail.txt" -f (Get-Date -Format 'yyyyMMdd_HHmm'))
```

`windows_only` 가 1 이면 혼합 인증이 꺼져 있다. 찾은 줄의 `Reason:`(또는 `원인:`) 문구가 원인이다. 암호 불일치는 "Password did not match", 로그인 없음은 "Could not find a login", 혼합 인증 꺼짐은 "configured for Windows authentication only", DB 접근 불가는 "Failed to open the explicitly specified database".
