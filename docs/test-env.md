# 시험 환경 준비와 1단계 확인 (0단계 + 1단계 완료 기준)

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
- 원천 로그인에 필요한 최소 권한은 4단계(CDC)에서 Debezium 실측으로 확정한다(plan.md R1). 지금 스크립트는 Debezium 문서 기준의 초안이다.

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

# ① 온라인 빌드 1회(의존성을 ~/.m2 에 받는다) + 단위 시험·라이선스 검사
./mvnw -B package 2>&1 | tee runs/${S}_p1_build_online.txt

# ② 오프라인 빌드(-o): 외부 다운로드 없이 다시 빌드되는지
./mvnw -B -o clean package 2>&1 | tee runs/${S}_p1_build_offline.txt

# ③ 인터넷을 끊고(공유기의 인터넷(WAN) 선만 뽑아 LAN 은 살린다) 두 DB 버전 출력
java -jar target/kdms.jar status 2>&1 | tee runs/${S}_p1_status.txt

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

## 7. 자주 막히는 곳

| 증상 | 원인 → 해결 |
|---|---|
| `설정 오류: … 환경 변수 KDMS_SRC_PASSWORD 가 없습니다` | `.env` 를 저장소 루트에 두지 않았거나 이름이 다르다. `--env-file 경로` 로 지정할 수도 있다 |
| 원천 `The TCP/IP connection … has failed` | 1433 방화벽·SQL Server TCP 설정. Mac `nc -vz 192.168.0.12 1433` 부터 |
| 원천 `PKIX path building failed` | 노트북 자체 서명 인증서. `.env` `KDMS_SRC_TRUST_CERT=true`(시험 환경만) |
| 대상 `password authentication failed for user "kdms_app"` | §3 에서 정한 암호와 `.env` `KDMS_TGT_PASSWORD` 가 다르다. §3 을 다시 실행하면 암호를 다시 맞춘다 |
| `plan` 이 `결과: 막힘` · `계산 컬럼 식을 PG 로 옮겨야 한다` | `config/kdms.yml` 의 `rules:` 가 비어 있다. `rules: config/kdms-rules.yml` |
| `schema` 가 `적용하지 않음: 대상에 이미 있다` | 이미 만든 테이블이다. 다시 만들려면 `--replace`(안의 데이터도 지워진다) |
| `nc` 는 되는데 Java 만 안 됨 | macOS 로컬 네트워크 권한: 시스템 설정 → 개인정보 보호 및 보안 → 로컬 네트워크 → 터미널 켜기 |
