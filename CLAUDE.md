# CLAUDE.md: KDMS 작업 규칙

> 상태: 확정 · 최종 갱신: 2026-10-09 · a43a5f8 · 근거: WORKLOG.md 머리, plan.md 초안 1 머리·§2·§6, README.md, 단계 PR(#1~#10)

**모든 세션은 작업 전에 이 파일과 [docs/plan.md](docs/plan.md)(개정 2)를 끝까지 읽는다.**
문서 목록은 [docs/README.md](docs/README.md), 결정은 [docs/decisions.md](docs/decisions.md)(DEC-xx), 오류·관찰은 [docs/issues.md](docs/issues.md), 관리 테이블은 [docs/database.md](docs/database.md).

## 1. 목적

MS-SQL 2019 → PostgreSQL 16 미니 DMS(Database Migration Service, 데이터 이관 서비스). 폐쇄망 금융권에서 외부 다운로드 없이 도는 단일 jar 를 만든다.
무중단(워터마크 기록 → 전체 적재 → 변경분 반복 반영 → 마지막 반영·검증·전환), 데이터 변환(규칙 파일), 검증(건수·합계·해시)을 안정적으로 한다.
범위는 [plan.md](docs/plan.md) §3, 단계와 진행 상태는 §6.

## 2. 절대 규칙

1. **고객사 이름·도메인·업무 용어·그것을 유추할 수 있는 단어, 고객 문서 내용은 저장소에 쓰지 않는다.** 문서에는 "고객사"로 쓴다.
2. **실행하지 않은 결과를 지어내지 않는다.** 클라우드 스레드는 노트북 DB 에 닿지 못하므로 노트북 결과를 적지 않는다. 클라우드 컨테이너 안 임시 DB 로 확인한 것은 "클라우드(노트북 아님)" 로 표시한다.
3. **비밀번호·키는 `.env` 에만.** 저장소에는 `.env.example` 만 둔다. 설정 파일에는 `${…}` 자리표시만 쓰고, 로그·`runs/`·WORKLOG·명령줄 어디에도 비밀번호를 남기지 않는다.
4. **행 값(개인정보)을 로그·관리 테이블·화면에 남기지 않는다**(PK 와 건수만). 예외인 `kdms.change_log.payload` 는 반영하면 바로 지운다(plan.md §7 R8, [database.md](docs/database.md)).
5. **KIS 저장소(`kodong75/KIS`)는 읽기 전용이다.** 수정·커밋하지 않는다. 가리킬 때는 `KIS:docs/issues.md A05` 처럼 쓴다.
6. **Debezium 은 포크하지 않는다.** Maven 의존성과 공개 확장점만 쓴다(DEC-03).
7. **폐쇄망**: 실행 중 외부 다운로드 0, 화면 JS·CSS 도 CDN(Content Delivery Network, 외부 배포망) 없이 jar 에 넣는다(DEC-09).
8. **오픈소스를 더하면 라이선스를 [licenses.md](docs/licenses.md) 에 기록한다.** 허용 목록 밖이거나 정보가 없으면 빌드가 실패한다(`license-maven-plugin`).
9. **main 에 직접 push 하지 않는다. 머지는 사용자가 한다**(§4).
10. **커밋은 파일 이름을 지정해 `git add` 한다**(`git add .` 금지). 커밋 전에 `git status` 로 `.env`·고객 문서·덤프가 섞이지 않았는지 사람 눈으로 본다.

## 3. 작업 분담

| 누가 | 어디서 | 하는 일 | 하지 않는 일 |
|---|---|---|---|
| 클라우드 스레드 | 클라우드 컨테이너 | 코드·문서·시험 SQL 작성, 단위 시험, 컨테이너 안 임시 MS-SQL·PG 로 확인, 커밋·draft PR | 노트북 DB 접속, 노트북 결과 기록 |
| Mac | 개발 Mac(Java 21, 기본 셸 zsh), 저장소 `/Users/kodong/Projects/KDMS` | 빌드, `kdms` 실행, 통합 시험, `runs/` 원문 저장·커밋 | DB 설치 |
| 노트북 | Windows 노트북 192.168.0.12, 저장소 `C:\Projects\KDMS`, PowerShell 7 | DB 만: SQL Server 2019 Developer `:1433`(로그인 `kodong_ms`, SQL Agent), PostgreSQL 16 Docker `:5432`(DB `kdms`, 역할 `kdms_app`). 시험 준비 SQL·원천 쓰기 부하(`test/sql/`) | `kdms.jar` 빌드·실행 |

- 노트북 준비·실행은 사용자가 직접 한다(DEC-44). 노트북을 재부팅하면 SQL Agent 를 손으로 시작해야 한다.
- Mac 시험은 채팅으로 명령을 한 단계씩 주고받는다. Mac 터미널 창 이름은 `main`(빌드·load·cutover·status), `sync`(`kdms sync`), `sub`(`kdms web` 등 보조)로 부른다. zsh 에는 `PIPESTATUS` 가 없다(`pipestatus[1]`, [issues.md](docs/issues.md) D05).
- 접속 정보·환경 구성은 [plan.md](docs/plan.md) §2, 실제 명령 순서는 [test-env.md](docs/test-env.md).
- 스레드는 작업 단위마다 하나, 동시에 최대 2개. 설계 그림 문서는 프로젝트의 문서 전용 스레드 하나가 맡는다(동시 2개 제한에서 예외).

## 4. 브랜치·PR 규칙

- **단계(또는 작업 단위) = 브랜치 하나 + draft PR 하나 + 스레드 하나.** 브랜치는 main 에서 만든다(`claude/…` 또는 작업 이름).
- **머지는 사용자가 한다**(squash). 머지 준비가 되면 Mac 터미널 명령을 줄별 설명과 함께 준다:

```bash
cd /Users/kodong/Projects/KDMS          # 저장소로 이동
gh pr ready N                           # draft 를 검토 가능 상태로
gh pr merge N --squash --delete-branch  # 커밋 하나로 합쳐 머지, 원격 브랜치 삭제
git pull                                # Mac 의 main 을 최신으로
```

- 단계 완료 기준은 plan.md §6 의 표. **Mac(노트북 DB)에서 실행한 `runs/` 파일로 보인다.** 클라우드 결과만으로는 완료로 보지 않는다.
- PR 설명: Before / After / How, 확인한 것과 확인하지 못한 것을 나눠 적는다. 실행 기록은 WORKLOG·`runs/` 로 링크한다.
- 머지 뒤 이어지는 일은 새 브랜치·새 PR 로 한다(머지된 PR 에 쌓지 않는다).

## 5. WORKLOG 형식

실행 결과 원문은 `runs/YYYYMMDD_HHMM_p<단계>_<이름>.txt`(예: `runs/20261008_1226_p4_sync1.txt`, 클라우드는 `_cloud_` 를 넣는다), [WORKLOG.md](WORKLOG.md) 에는 경로와 요약을 적는다.

```
## YYYY-MM-DD HH:MM · 단계 · 작업명 · Mac/노트북/클라우드
- 목적 / 환경
- 실행 명령·SQL(그대로)
- 결과(건수·시간)
- 오류 원문
- 원인 → 해결 → 재실행 결과
```

단계가 머지되면 그 단계 기록 뒤에 "단계 요약" 항목을 하나 둔다:

```
## YYYY-MM-DD HH:MM · N단계 · 단계 요약 · 클라우드
- 결과: 완료 기준과 그 근거(PR 번호, 위 항목 시각)
- 이슈 ID: docs/issues.md 의 ID
- 남은 일
- runs 파일
```

- "오류 원문 / 원인 → 해결" 은 [issues.md](docs/issues.md) 에 ID 로 옮긴다.
- **시각은 KST(한국 표준시, UTC+9) 하나로 쓴다**(DEC-45). WORKLOG 머리, `runs/` 파일 이름, 문서의 시각 모두 KST. 클라우드 컨테이너는 시계가 UTC 이므로 `TZ=Asia/Seoul date +%Y%m%d_%H%M` 처럼 KST 로 바꿔 적는다. 시각대를 섞어 쓸 수밖에 없는 곳(원문 인용 등)은 `+09:00`·`UTC` 를 붙인다.
- 2026-10-09 이전 클라우드 기록은 UTC 로 적혀 있었다. WORKLOG 머리와 문서의 시각은 KST 로 고쳤고, `runs/*_cloud_*.txt` 파일 이름과 그 안의 시각은 원문 그대로 UTC 다(예: WORKLOG 2026-10-08 11:57 = `runs/20261008_0257_p4_cloud_e2e.txt`).

## 6. 문서 형식

- **머리 줄**: 제목 바로 아래 한 줄.
  `> 상태: 초안|확정|진행 중|완료|폐기 · 최종 갱신: YYYY-MM-DD · <커밋 7자리> · 근거: …`

| 상태 | 뜻 |
|---|---|
| 초안 | 사용자 검토 전 |
| 확정 | 사용자가 정한 기준. 바꾸려면 결정(DEC)이 필요 |
| 진행 중 | 단계마다 덧붙이는 문서 |
| 완료 | 그 단계가 머지돼 더 덧붙일 일이 없음(틀린 것만 고친다) |
| 폐기 | 더 쓰지 않음. 지우지 않고 대신 볼 문서를 링크 |

  - 커밋: 그 문서 내용을 대조한 main 커밋. 문서를 고치는 커밋은 자기 해시를 담을 수 없으므로 고칠 때 기준으로 삼은 커밋을 쓴다.
  - 근거: WORKLOG 항목 시각, `runs/` 파일, PR 번호처럼 다시 찾아볼 수 있는 것.
- **목차**: 문서를 새로 만들거나 상태가 바뀌면 [docs/README.md](docs/README.md) 의 표도 고친다. 저장소 README.md 는 이 목차로 링크만 둔다.
- **결정 기록**: 결정은 [docs/decisions.md](docs/decisions.md) 에 DEC 번호로 한 줄 추가하고, 본문에는 `(DEC-xx)` 만 쓴다. 결정을 바꾸면 새 번호를 쓰고 옛 줄의 결정 칸 앞에 `바뀜 → DEC-yy` 를 붙인다.
- **이슈**: 오류·관찰은 [docs/issues.md](docs/issues.md) 에 ID 로. KIS 에 같은 현상이 있으면 `KIS:docs/issues.md A05` 로 연결한다.
- **같은 내용은 한 곳에만** 쓰고 다른 곳은 링크한다.
- 한국어로 쓴다. 약어는 처음 나올 때 괄호에 원어와 한글 뜻을 쓴다: CDC(Change Data Capture, 변경 데이터 캡처).
- 설계 그림 문서(`docs/design/`)의 형식·스타일은 [docs/design/README.md](docs/design/README.md)(DEC-41, 색은 DEC-46).
