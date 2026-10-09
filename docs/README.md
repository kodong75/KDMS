# KDMS 문서 목록

> 상태: 진행 중 · 최종 갱신: 2026-10-09 · a43a5f8 · 근거: 저장소 docs/·루트 문서, PR #1~#10

문서를 새로 만들거나 상태가 바뀌면 이 표를 함께 고친다. 상태 뜻과 머리 줄 형식은 [CLAUDE.md](../CLAUDE.md) §6.

## 1. 규칙·기록

| 문서 | 종류 | 상태 | 한 줄 설명 |
|---|---|---|---|
| [../CLAUDE.md](../CLAUDE.md) | 규칙 | 확정 | 목적, 절대 규칙, 클라우드·Mac·노트북 분담, 브랜치·PR, WORKLOG·문서 형식 |
| [../README.md](../README.md) | 안내 | 진행 중 | 저장소 소개, 빌드·실행 명령, 폴더 구조 |
| [../WORKLOG.md](../WORKLOG.md) | 기록 | 진행 중 | 실행 기록과 단계 요약. 결과 원문은 `runs/` |
| [decisions.md](decisions.md) | 결정 | 진행 중 | 설계·운영 결정 DEC-01~ (날짜·이유·버린 대안·영향 문서) |
| [issues.md](issues.md) | 이슈 | 진행 중 | 실행 중 만난 오류·관찰(ID·증상·원인·조치·상태·근거) |

## 2. 계획·설계

| 문서 | 종류 | 상태 | 한 줄 설명 |
|---|---|---|---|
| [plan.md](plan.md) | 계획 | 진행 중 | 개정 2. 구조, MVP 범위, 설계, 규칙 파일, 단계표(상태·완료 근거), 위험, 시험 계획 |
| [database.md](database.md) | 설계 | 확정 | 관리 스키마 `kdms` 테이블 정의서, 관계도, 코드값·상태 전이, 버전 이력(v1~v3) |
| [normalization.md](normalization.md) | 설계 | 확정 | 검증용 값 정규화 규칙(KIS 규칙 + KDMS 구현 위치) |
| [design/README.md](design/README.md) | 설계 | 진행 중 | 그림 문서(D01 구성도·D02 SW 아키텍처·D03 이관 흐름도·D06 일정·D07 변환 규칙 매핑표) 형식·스타일·다시 만들기. 목록은 [design/index.html](design/index.html) |

## 3. 단계 문서

| 문서 | 종류 | 상태 | 한 줄 설명 |
|---|---|---|---|
| [schema-conversion.md](schema-conversion.md) | 단계(2) | 완료 | `kdms plan`·`schema`, 세 단계 DDL, 규칙이 DDL 에 들어가는 곳, KIS mock.sql 비교 |
| [load-verify.md](load-verify.md) | 단계(3) | 완료 | `kdms load`·`verify`, 구간·재시작, 값 변환, 검증·행 차이, NUL 결정 |
| [cdc.md](cdc.md) | 단계(4) | 완료 | `kdms sync`·`reset`, 워터마크와 적재, 반영 규칙, drain 조건, Debezium 설정, 원천 권한 |
| [cutover.md](cutover.md) | 단계(5) | 완료 | `kdms cutover`·`status`·`web`, 전환 6단계, IDENTITY·SEQUENCE, 시나리오 S1~S4 |

## 4. 환경·운영

| 문서 | 종류 | 상태 | 한 줄 설명 |
|---|---|---|---|
| [test-env.md](test-env.md) | 절차 | 진행 중 | 노트북·Mac 준비와 단계별(1~5) 확인 명령, 자주 막히는 곳(§7) |
| [licenses.md](licenses.md) | 점검 | 진행 중 | 오픈소스 라이선스, 자동 보고서 결과, 뺀 의존성 |

6단계 문서(`runbook.md`·`rehearsal.md`)는 PR #11(draft)에 있고 main 에는 아직 없다.
