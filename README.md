# KDMS

> 상태: 진행 중 · 최종 갱신: 2026-10-09 · a43a5f8 · 근거: 5단계 PR #10 머지 시점의 명령·구조

MS-SQL 2019 → PostgreSQL 16 미니 DMS(Database Migration Service, 데이터 이관 서비스). 폐쇄망 금융권용 단일 실행 jar.

- 작업 규칙: [CLAUDE.md](CLAUDE.md)
- 문서 목록(계획·단계 문서·결정·이슈·관리 테이블·시험 환경·라이선스): [docs/README.md](docs/README.md). 지금 단계는 [docs/plan.md](docs/plan.md) §6
- 실행 기록: [WORKLOG.md](WORKLOG.md), 결과 원문 `runs/`

## 빌드·실행 (Java 21)

```bash
./mvnw -B package                       # target/kdms.jar (단위 시험·라이선스 검사 포함)
cp .env.example .env                    # 비밀번호 채우기
cp config/kdms.example.yml config/kdms.yml
java -jar target/kdms.jar status        # 원천·대상 접속, 버전, CDC·관리 스키마 상태
java -jar target/kdms.jar init          # 대상 PG 에 관리 스키마 kdms 생성(재실행 안전)
java -jar target/kdms.jar plan          # 원천 카탈로그 + 변환 규칙 → 보고서·DDL(out/<작업>/). 아무 DB 도 안 바꾼다
java -jar target/kdms.jar schema        # 위 DDL(적재 전 단계)을 대상 PG 에 적용, 작업 등록
java -jar target/kdms.jar sync          # (다른 터미널) 변경분 수집·반영. "워터마크 기록" 뒤에 load. 쓰기를 멈춘 뒤 sync --drain (docs/cdc.md)
java -jar target/kdms.jar load          # 전체 적재(COPY, 구간 병렬). 다시 실행하면 끝난 구간은 건너뛰고 이어서. 원천 쓰기가 없으면 --no-cdc
java -jar target/kdms.jar verify        # 건수·합계·해시 검증, 다르면 차이 행 PK. 불일치면 종료 코드 5
java -jar target/kdms.jar cutover --yes # 원천 쓰기를 멈춘 뒤 전환: 마지막 반영 → 검증 → setval → FK, 소요 시간 = 예상 다운타임(docs/cutover.md)
java -jar target/kdms.jar check --probe # 전환 뒤 점검: 다음 값·제약·인덱스·계산 컬럼, 입력 시험은 되돌림(docs/runbook.md §7)
java -jar target/kdms.jar reset --yes   # 대상 테이블을 비우고 적재·동기화 기록을 지워 적재 전으로(처음부터 다시)
java -jar target/kdms.jar web           # 웹 화면 http://127.0.0.1:8080: 단계·지연·적재·검증·전환과 실행 버튼
```

## 구조

| 경로 | 내용 |
|---|---|
| `src/main/java/kdms/` | 패키지별 책임은 plan.md §4.1 (`config`, `catalog`, `rules`, `ddl`, `load`, `cdc`, `apply`, `verify`, `cutover`, `state`, `web`, `cli`) |
| `src/main/resources/db/kdms-schema*.sql` | 관리 테이블(대상 PG `kdms` 스키마). 버전마다 파일 하나. 정의서는 [docs/database.md](docs/database.md) |
| `src/main/resources/kdms-rules.yml` | 변환 규칙 기본값(plan.md §5) |
| `config/` | 작업 설정 예시, 작업별 규칙 예시, 라이선스 허용 목록 |
| `test/sql/` | 노트북에서 사람이 실행하는 시험 준비 SQL(MS-SQL 복원·CDC·권한·쓰기·캡처 Job·CDC 정리, PG DB·역할) |
| `scripts/` | 노트북 SQL 실행(`Invoke-KdmsSql.ps1`), 리허설(`rehearsal.sh` Mac, `Invoke-KdmsRehearsal.ps1` 노트북) |
