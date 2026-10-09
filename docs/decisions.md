# 결정 기록

> 상태: 진행 중 · 최종 갱신: 2026-10-09 · a43a5f8 · 근거: plan.md §1.1·§1.2, 단계 문서, PR #1~#10 설명, WORKLOG

설계·운영 결정을 한 곳에 모은다. 본문 문서에는 `(DEC-xx)` 만 쓰고 이유는 여기에 둔다. 쓰는 법은 [CLAUDE.md](../CLAUDE.md) §6.
"사용자 결정" 은 사용자가 정한 것, "채택" 은 계획·PR 에서 정하고 사용자가 그 PR 을 머지해 받아들인 것이다(사용자가 따로 승인한 기록은 없다).

| 번호 | 날짜 | 결정 | 이유 | 버린 대안 | 영향 문서 |
|---|---|---|---|---|---|
| DEC-01 | 2026-10-01 | 사용자 결정: Java 17 이상, Spring Boot, Maven | 사용자 지정(이유 기록 없음) | — | plan.md §1.1 |
| DEC-02 | 2026-10-01 | 사용자 결정: jar 하나에 엔진 + 내장 웹 화면(localhost) + 화면 없는 CLI(Command Line Interface, 명령줄) 모드 | 폐쇄망에서 파일 하나로 실행(plan.md 머리) | — | plan.md §1.1·§4.1, cutover.md §4 |
| DEC-03 | 2026-10-01 | 사용자 결정: 변경분은 CDC(Change Data Capture, 변경 데이터 캡처). Debezium Embedded Engine + SQL Server 커넥터, Kafka 없이. **포크하지 않고 Maven 의존성으로만** | 사용자 지정(이유 기록 없음) | Debezium 포크 | plan.md §1.1, cdc.md |
| DEC-04 | 2026-10-01 | 사용자 결정: 전체 적재는 자체 적재기. PG `COPY`, 테이블·구간 병렬 | 사용자 지정(이유 기록 없음). CDC 는 `snapshot.mode=no_data` 로 스키마만 읽는다 | Debezium 데이터 스냅숏 | plan.md §4.3, load-verify.md |
| DEC-05 | 2026-10-01 | 사용자 결정: 순서 = 워터마크(CDC LSN(Log Sequence Number, 로그 순번)) 기록 → 전체 적재 → 변경분 반복 반영 → 마지막 반영·검증·전환 | KIS:docs/zero-downtime.md §1 과 같은 순서 | — | plan.md §1, cdc.md §2 |
| DEC-06 | 2026-10-01 | 사용자 결정: 변환 규칙(자료형·콜레이션·끝 공백·datetime 정밀도·money·bit·uniqueidentifier·IDENTITY→시퀀스 등)은 설정 파일로 분리 | 사용자 지정(이유 기록 없음) | — | plan.md §5, schema-conversion.md |
| DEC-07 | 2026-10-01 | 사용자 결정: 검증은 건수·합계·해시 | KIS:docs/normalization.md 규칙을 그대로 쓴다 | — | plan.md §4.7, normalization.md |
| DEC-08 | 2026-10-01 | 사용자 결정: 워터마크·진행 상태는 대상 PG 관리 테이블에. 재시작하면 이어서 | 사용자 지정(이유 기록 없음) | — | plan.md §4.2, database.md |
| DEC-09 | 2026-10-01 | 사용자 결정: 폐쇄망. 모든 라이브러리 jar 포함, 실행 중 외부 다운로드 0, 화면 JS·CSS 도 CDN 없이. 나중에 jlink 로 JRE(Java Runtime Environment, 자바 실행 환경) 포함 설치본 | 폐쇄망 금융권(plan.md 머리) | — | plan.md §1.1·§8.4, licenses.md |
| DEC-10 | 2026-10-01 | 채택(PR #1): Java 21 LTS 로 빌드·실행, 바이트코드 17(`maven.compiler.release=17`) | Debezium 3.7 커넥터 jar 가 Java 17 바이트코드. 17 환경에서도 돌게 | 17 로 빌드 | plan.md §1.2, pom.xml |
| DEC-11 | 2026-10-01 | 채택(PR #1·#2): Spring Boot 4.1.x(4.1.1), `kafka.version` 을 Debezium 기준(4.3.1)으로 고정 | 지원 버전. Boot 관리 Kafka(4.2.1)와 Debezium 이 쓰는 Kafka 가 다르다(R3) | Boot 3.x | plan.md §1.2·§7 R3, licenses.md |
| DEC-12 | 2026-10-01 | 채택(PR #1): Debezium 3.7.0.Final | 그날 최신 안정판 | — | plan.md §1.2, licenses.md |
| DEC-13 | 2026-10-01 | 채택(PR #1): 수집과 반영을 분리. Debezium → `kdms.change_log` 저장 → 반영기가 순서대로 적용 | 전체 적재가 며칠 걸려도 원천 CDC 보존 기간(기본 3일)에 묶이지 않고, 재시작·재반영이 쉽다 | 이벤트를 바로 대상에 적용 | plan.md §4.4, cdc.md §3 |
| DEC-14 | 2026-10-01 | 채택(PR #1, 4단계 보완): Debezium 오프셋·스키마 이력은 `debezium-storage-jdbc` 로 대상 PG 에. 4단계에서 작업마다 표를 나눔(`debezium_offset_<job_id>`) | "상태는 대상 PG 한 곳". `JdbcOffsetBackingStore` 가 저장 때마다 표 전체를 다시 써서 작업별로 나눠야 한다 | 파일 오프셋 저장소 | plan.md §1.2, cdc.md §3, database.md |
| DEC-15 | 2026-10-01 | 채택(PR #1·#2): 관리 테이블은 앱이 자체 DDL 스크립트로 만들고(`schema_version`), 바꿀 때는 기존 파일을 고치지 않고 다음 버전 파일을 더한다 | Flyway 등 의존성 없이. 테이블 수가 적다 | Flyway, Liquibase | plan.md §1.2, database.md §4 |
| DEC-16 | 2026-10-01 | 채택(PR #1): 화면은 서버 렌더링(Thymeleaf) + 직접 작성한 작은 JS·CSS | 라이선스·CDN·보안 점검 대상을 줄인다 | React 등 프런트엔드 라이브러리 | plan.md §1.2, cutover.md §4 |
| DEC-17 | 2026-10-01 | 채택(PR #1): CLI 해석은 picocli | Apache-2.0, 의존성 없음 | — | plan.md §1.2 |
| DEC-18 | 2026-10-01 | 채택(PR #1): 시험 원천은 KIS `MIG_MOCK` 을 백업·복원한 사본 `KDMS_MOCK`(나중에 `KDMS_SITE`) | CDC 를 켠 테이블은 TRUNCATE 가 안 되고, KIS 스크립트가 쓰는 `MIG_*` DB 를 건드리지 않는다 | `MIG_MOCK` 직접 사용 | plan.md §1.2·§2.1, test-env.md §2 |
| DEC-19 | 2026-10-01 | 채택(PR #1): 대상은 노트북 PG 의 새 DB `kdms`, 전용 로그인 역할 `kdms_app`(superuser 아님) | KIS 의 `mig` DB 와 분리, 최소 권한 | superuser 접속 | plan.md §1.2, test-env.md §3 |
| DEC-20 | 2026-10-01 | 채택(PR #1): 검증은 원천·대상 각 DB 에서 계산하고 숫자만 JDBC 두 개로 가져와 비교 | 대상 PG 에 확장 설치가 필요 없다 | KIS 방식 tds_fdw 로 PG 한 곳에서 비교 | plan.md §4.7, normalization.md §0 |
| DEC-21 | 2026-10-01 | 채택(PR #1): 시험 환경 JDBC 는 `trustServerCertificate=true`, 운영 설정 예시는 인증서 검증을 켠 채로 | 노트북 자체 서명 인증서(KIS:docs/issues.md E03) | — | plan.md §2, test-env.md §7 |
| DEC-22 | 2026-10-01 | 채택(PR #2): Kafka Connect REST 서버 전용 의존성(Jetty·Jersey·JAX-RS·JAXB·jose4j·swagger)과 압축 네이티브 라이브러리(zstd·snappy·lz4)를 뺀다 | Embedded 에서 쓰지 않음. 라이선스 검토 대상 축소(`DebeziumClasspathTest` 로 엔진 동작 확인) | 모두 포함 | licenses.md §5.2, pom.xml |
| DEC-23 | 2026-10-01 | 채택(PR #4): 대상 DDL 을 세 단계로 나눈다(적재 전 / 적재 뒤 UNIQUE·인덱스 / 전환 때 FK). FK 는 전환 직전에 켠다 | 적재 속도, 적재·반영 순서와 FK 충돌 방지 | 한 번에 생성 | schema-conversion.md §2, plan.md §3.1 |
| DEC-24 | 2026-10-01 | 채택(PR #4): 모든 문자 컬럼에 `COLLATE "C"` 를 명시하고, 원천 CI(Case-Insensitive, 대소문자 무시) UNIQUE 는 `UNIQUE INDEX … (lower(col))`(`ci_unique: lower_index`) | DB 기본값에 기대지 않음. 대상에서도 `kim01`/`Kim01` 중복을 막는다(KIS B14) | 비결정 ICU 콜레이션(`ci`), citext | schema-conversion.md §3, kdms-rules.yml |
| DEC-25 | 2026-10-01 | 채택(PR #4): 계산 컬럼은 `GENERATED ALWAYS AS (…) STORED`. PG 식이 없으면 오류로 멈춤(`value_with_warning` 로만 일반 컬럼 허용). 적재·반영에서 뺀다 | CDC 가 계산 컬럼을 캡처하지 않는다(KIS:docs/zero-downtime.md §4-11) | KIS mock.sql 처럼 일반 컬럼 + COMMENT | schema-conversion.md §3·§4, plan.md §4.4 |
| DEC-26 | 2026-10-01 | 채택(PR #4): 규칙으로 옮기지 못한 것(규칙 없는 타입, 번역 못 한 기본값 식, 63바이트 넘는 이름)은 오류로 멈추고(종료 코드 3), 트리거·CHECK·뷰·SP·함수·SYNONYM 은 경고·목록만 | 조용히 틀린 DDL 을 만들지 않는다. 프로그램 객체는 KIS:docs/appcompat.md 로 수작업 | 자동 변환 | schema-conversion.md §1·§3, plan.md §3.2 |
| DEC-27 | 2026-10-06 | 채택(PR #8): NUL 문자는 기본 `nul_char: fail` 유지, KDMS_MOCK `dbo.issuer.issuer_nm` 만 `replace`(U+FFFD) | `replace` 는 대상에서 다시 찾을 수 있고, 다른 컬럼에 새 NUL 이 생기면 멈춰서 알린다 | `strip`(흔적 없이 붙어 버림), 전부 `replace` | load-verify.md §4, config/kdms-rules.yml, issues.md A01 |
| DEC-28 | 2026-10-06 | 채택(PR #8): 구간 행과 `load_chunk` DONE 표시를 대상 한 트랜잭션에 커밋. 다시 실행하면 DONE 이 아닌 구간만 처음부터 | 커밋된 구간 = DONE 이라 범위 DELETE 가 필요 없고 대상 쪽 순서에 기대지 않는다 | plan.md §4.3 ④ 의 "구간을 먼저 DELETE 하고 다시 넣기" | load-verify.md §2.2 |
| DEC-29 | 2026-10-06 | 채택(PR #8): 구간 조건은 원천에서만 쓰고, 검증의 행 차이는 PK 정규화 문자열 MD5 앞 2바이트 묶음으로 좁힌다 | 대상(C)과 원천(CI) 콜레이션이 달라 문자 PK 순서가 다르다 | plan.md §4.7 의 "PK 구간별로 좁히기" | load-verify.md §2.1·§3, normalization.md §5 |
| DEC-30 | 2026-10-08 | 채택(PR #9): 대상 테이블에 트리거·FK 가 있으면 `kdms sync` 가 시작하지 않는다 | `session_replication_role = replica` 는 PG superuser 만 바꿀 수 있다(DEC-19). 트리거가 돌면 이력이 두 번 생긴다(KIS G13) | plan.md §4.4 의 `session_replication_role` 방식 | cdc.md §1.1·§4, plan.md §4.4 |
| DEC-31 | 2026-10-08 | 채택(PR #9): CDC 모드에서는 적재가 끝나도 UNIQUE·보조 인덱스를 만들지 않고 drain 뒤(5단계부터 `kdms cutover` ③)에 만든다 | 변경을 순서대로 다시 적용하는 동안 잠깐 UNIQUE 가 겹칠 수 있다 | 적재 직후 생성 | cdc.md §4, cutover.md §2 |
| DEC-32 | 2026-10-08 | 채택(PR #9): `debezium-storage-jdbc` 3.7.0 재시작 버그를 `JdbcOffsetBackingStore` 상속 클래스 `KdmsOffsetStore`(ServiceLoader `kdms-jdbc`)로 우회하고 `debezium-storage-common` 을 의존성에 넣는다 | 포크 없이(DEC-03) 공개 확장점만으로 해결. Debezium 을 올릴 때 다시 필요한지 본다 | Debezium 포크, 파일 오프셋 | cdc.md §6, licenses.md, issues.md B01·B02 |
| DEC-33 | 2026-10-08 | 채택(PR #9): `kdms load` 는 워터마크가 있어야 시작한다(원천 쓰기가 없을 때만 `--no-cdc`). T-C02(적재 뒤 워터마크 실험 빌드)는 만들지 않고 이 거부로 막는다 | 워터마크 앞 시점을 읽는 적재를 구조적으로 막는다 | 실험 빌드로 차이 확인 | cdc.md §2·§8, load-verify.md |
| DEC-34 | 2026-10-08 | 채택(PR #9): 반영한 `change_log` 행은 반영 트랜잭션 안에서 바로 지운다 | 행 원문에 개인정보(R8), 표 크기(R7) | 검증 뒤 일괄 삭제 | cdc.md §3, plan.md §7 R7·R8 |
| DEC-35 | 2026-10-08 | 채택(PR #9): drain 은 "따라잡은 순간의 원천 시각(mark) 뒤에 시작한 캡처 Job 로그 훑기가 끝나고, 그 뒤 커밋이 없을 때" 끝난다 | 원천 최대 LSN 만 보면 캡처되지 않은 커밋을 놓친다(issues.md B03) | 원천 최대 LSN 까지 반영, plan.md §4.5 의 "0건 2회" | cdc.md §5, cutover.md §2 |
| DEC-36 | 2026-10-08 | 채택(PR #10): 지연 = 원천 마지막 커밋 시각 − 반영할 수 있는(적재가 끝난 테이블의) 가장 오래된 미반영 변경의 커밋 시각. 적재 전 테이블의 변경은 `대기` 괄호로 따로 센다 | 적재 전 지연이 수백 초로 과하게 보였다(issues.md B06) | plan.md §4.4 의 "원천 max LSN 시각 − 마지막 반영 이벤트 커밋 시각" | cdc.md §1, cutover.md §5 |
| DEC-37 | 2026-10-08 | 채택(PR #10): `kdms cutover --yes` 는 6단계(마지막 반영 → PK 없는 테이블 재적재 → UNIQUE·인덱스 → 검증 → setval → FK). 검증 불일치면 멈춤(종료 코드 5), drain 시간 초과 6(`--max-wait` 기본 600초). 다시 실행하면 처음 단계부터(모든 단계 멱등) | 전환을 한 명령으로, 중간에 죽어도 그대로 다시 실행 | 단계별 수동 명령 | cutover.md §2 |
| DEC-38 | 2026-10-08 | 채택(PR #10): 5단계 완료 기준의 시나리오 S1~S4 정의(정상 전환 / 불일치면 전환 안 함 / 중단과 재실행 / 전환 뒤 새 입력) | 계획서에 S1~S4 정의가 없었다 | — | cutover.md §6, plan.md §6 |
| DEC-39 | 2026-10-08 | 채택(PR #10): IDENTITY 다음 값은 원천 `IDENT_CURRENT`(`sys.identity_columns.last_value`) 기준, SEQUENCE 는 `current_value` 기준. 원천 값은 전환 ⑤ 에서 다시 읽는다 | 삭제·RESEED 로 `MAX(id)` 보다 클 수 있다(KIS A09). 쓰기를 멈춘 뒤 값이어야 한다 | `MAX(id)` 기준, 계획 때 값 | cutover.md §2.1 |
| DEC-40 | 2026-10-08 | 채택(PR #10): 웹 화면은 CLI 와 같은 명령을 버튼으로 실행. 지우는 명령(`reset`, `schema --replace`)은 화면에 없음. POST 는 `X-KDMS-Token` 필수, 기본 `127.0.0.1` 바인딩, 인증 없음 | 사용자가 화면을 요청. CSRF(Cross-Site Request Forgery, 사이트 간 요청 위조) 차단, 실수로 지우는 일 방지 | 인증 있는 원격 화면(MVP 뒤, plan.md §3.2) | cutover.md §4 |
| DEC-41 | 2026-10-06 | 바뀜 → DEC-46 (색만). 사용자 결정(PR #6): 설계 그림 문서는 원본 HTML+SVG(`docs/design/`), 배포본 PDF(A4 가로)·PPTX(16:9 한 장, 모든 항목 편집 가능한 도형·텍스트, 이미지 금지)·PNG. 흰 배경·네이비·스카이블루·Pretendard·Material Symbols | git 으로 이력 관리, 폐쇄망에서 열림, 발표 PC 에서 고칠 수 있음 | 그림 이미지 PPTX | docs/design/README.md, licenses.md §6 |
| DEC-42 | 2026-10-06 | 사용자 결정: 설계 문서는 지금 필요한 것만(D01·D02·D03·D06), 나머지는 해당 단계에서 | 단계마다 바뀌는 내용을 미리 그리지 않는다 | 전체 문서를 먼저 | docs/design/README.md |
| DEC-43 | 2026-10-06 | 채택(PR #4·#8): MVP 대상 테이블 = 노트북 실측 `KDMS_MOCK` dbo 테이블 7개(모두 PK 있음). KIS #23 의 `dbo.file_attach` 는 노트북 `MIG_MOCK` 에 없어 빠짐 | plan.md §3.1 "PK 있는 테이블 전부" 를 실측으로 확인 | — | plan.md §3.1·§10, schema-conversion.md §4 |
| DEC-44 | 2026-10-08 | 채택(관행): 0단계 노트북 작업(복원·CDC 켜기·PG 역할)과 노트북 쪽 실행은 사용자가 직접 한다. Remote Control 은 쓰지 않았다 | 3~5단계 노트북 기록이 모두 사용자 실행(WORKLOG 2026-10-08 11:00·12:26·15:44) | Remote Control 로 노트북 세션에 맡김 | plan.md §10, CLAUDE.md §3 |
| DEC-45 | 2026-10-09 | 사용자 결정(2026-10-09, KST 확정): 기록·문서의 시각은 KST(UTC+9) 하나로 쓴다. 과거 클라우드 기록은 WORKLOG·문서 시각만 KST 로 고치고 `runs/` 파일 이름은 그대로 둔다 | 사람·노트북·Mac·고객사 작업 시간이 모두 KST 이고 kdms 출력도 지역 시각(`+09:00`). 클라우드 UTC·Mac KST 가 섞여 있었다 | 모두 UTC(서버 로그 관행), 과거 runs 파일 이름까지 바꾸기(다른 PR 의 참조가 깨짐) | CLAUDE.md §5, WORKLOG.md, plan.md §6, issues.md |
| DEC-46 | 2026-10-09 | 사용자 결정(PR #7, 확정. 나중에 DEC-41 색으로 돌아갈 수 있음): 설계 그림 색을 녹색 계열로(진한 녹색 `#0B3D2E`·`#1E6B4F`·밝은 녹색 `#2E9E6E`·테두리 `#9DD3B8`·바탕 `#EEF8F3`). 나머지 형식은 DEC-41 과 사용자 그림 규칙(`/diagram-style`) 그대로 | 이 프로젝트를 녹색으로 구분하고 싶음 | 네이비·스카이블루(DEC-41) | docs/design/README.md |
