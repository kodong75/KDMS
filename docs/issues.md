# 이슈 목록 (1~5단계, 2026-10-01~08)

> 상태: 진행 중 · 최종 갱신: 2026-10-10 · d142fde · 근거: WORKLOG.md 의 "오류 원문 / 원인 → 해결" 항목, runs/, PR #3·#4·#5 설명

[WORKLOG.md](../WORKLOG.md) 의 오류·관찰을 다시 볼 수 있게 묶었다. 근거는 WORKLOG 항목 시각(KST, DEC-45)과 `runs/` 파일(클라우드 파일 이름은 UTC). 결정은 [decisions.md](decisions.md), 함정 근거는 KIS 이슈(`KIS:docs/issues.md A02` 처럼).
형식은 KIS:docs/issues.md 와 같다.

상태: **해결** = 저장소에 반영되고 다시 실행해 확인함 / **해결(재실행 전)** = 고쳤지만 DB 로 다시 실행하지 않음 / **확인** = 원인을 추정만 했거나 다시 볼 일 / **운영** = 절차상 주의

## A. 이관 데이터·값

| ID | 증상 | 원인 | 조치 | 상태 | 근거 |
|---|---|---|---|---|---|
| A01 | 기본 규칙(`nul_char: fail`)에서 `dbo.issuer` 구간 2 적재 실패(T-L09) | KIS 가 `issuer_nm` 1행에 심은 NUL 문자. PG 문자 타입은 NUL 을 저장 못 함(KIS:docs/issues.md A02) | `config/kdms-rules.yml` 에 이 컬럼만 `nul_char: replace`(DEC-27). 2단계 `plan --scan` 도 같은 이유로 막힘 | 해결 | 10-06 23:40, `runs/20261006_1440_p3_cloud_load_verify.txt` |
| A02 | NUL `replace` 뒤 검증에서 issuer 해시가 전부 다름 | 원천 쪽 REPLACE 에 코드페이지 1252 이진 콜레이션을 써 varchar 변환에서 한글이 `?` 로 바뀜 | 행 해시와 같은 `Latin1_General_100_BIN2_UTF8` 로(load-verify.md §4) | 해결 | 10-06 23:40 |
| A03 | 같은 KDMS_MOCK 인데 클라우드 48,047행, 노트북 48,053행 | 노트북 원천 건수 차이로 추정(검증은 각 환경에서 원천 = 대상 일치) | 원천 건수를 따로 세지 않았다 | 확인 | 10-08 11:00 |

## B. 변경분 수집·반영 (CDC)

| ID | 증상 | 원인 | 조치 | 상태 | 근거 |
|---|---|---|---|---|---|
| B01 | 엔진 시작 시 `NoClassDefFoundError: io/debezium/spi/storage/DefaultOffsetStorageReader` | `debezium-storage-jdbc` 가 `debezium-storage-common` 을 provided 로만 선언 | 의존성 추가(Apache-2.0, licenses.md, DEC-32) | 해결 | 10-08 11:57 |
| B02 | 오프셋이 저장된 뒤 다시 시작하면 `ClassCastException: class java.lang.Integer cannot be cast to class java.lang.Long` | storage-jdbc 3.7.0 이 오프셋 JSON 의 `event_serial_no` 를 Integer 로 돌려주고 커넥터는 Long 으로 꺼냄 | `KdmsOffsetStore` 로 Long 변환(포크 없음, cdc.md §6, DEC-32). Debezium 을 올릴 때 아직 필요한지 다시 본다 | 해결 | 10-08 11:57 |
| B03 | 쓰기 도중에 시작한 `--drain` 이 쓰기가 끝나기 전에 끝나 검증 27/30 | "마지막 커밋 뒤 시작한 훑기" 를 기준으로 삼아 그 훑기가 잡은 커밋에 스스로 만족 | 따라잡은 순간의 원천 시각 뒤에 시작한 훑기를 기준으로, 확인 뒤 다시 조회(cdc.md §5, DEC-35) → 쓰기 끝 7초 뒤 끝남, 30/30 | 해결 | 10-08 11:57, `runs/20261008_0256_p4_cloud_drain_writes.txt` |
| B04 | `kdms sync` 에 SIGTERM 을 보내도 프로세스가 90초 남음 | 종료 훅이 `System.exit` 에서 멈춘 main 스레드를 join | 작업 끝 latch 로 기다림 → 1초 안에 끝남 | 해결 | 10-08 11:57 |
| B05 | 캡처 Job 을 멈춰도 지연이 0.0초 | 지연 계산이 캡처된 커밋(`lsn_time_mapping`)만 봄 | 캡처 Job 마지막 로그 훑기가 15초보다 오래되면 그 시간을 지연으로 쓰고 경고, `REPLICATION` 대기 표시(T-C09, plan.md R2) | 해결 | 10-08 11:57, `runs/20261008_0306_p4_cloud_tc09.txt` |
| B06 | 적재 전 첫 수집 때 지연이 348~369초로 표시 | 아직 반영한 변경이 없으면 워터마크 LSN 커밋 시각부터 잼(원천이 조용했던 시간까지 포함) | 5단계에서 지연 정의 변경(DEC-36) → 노트북 적재 전 지연 0.0초 확인 | 해결 | 10-08 12:26 `runs/20261008_1226_p4_sync1.txt` → 10-08 15:44 `runs/20261008_1547_p5_sync.txt` |
| B07 | `kdms sync` 를 다시 띄운 뒤 원천 커밋이 없으면 cutover 의 마지막 반영이 "스트리밍 시작 전" 에서 안 끝남 | SQL Server 커넥터가 새 커밋 전에는 첫 이벤트·하트비트를 주지 않아 스트리밍 위치가 비어 있음 | 저장된 오프셋의 `commit_lsn` 으로 위치를 채움(cutover.md §5) | 해결 | 10-08 13:10 |
| B08 | `sync` 시작 줄의 워터마크 기록 시각이 UTC 로 찍혀 다른 줄(지역 시각)과 다름 | 시각 형식 | 지역 시각으로(SyncRunner, 단위 시험 통과) | 해결(재실행 전) | 10-08 12:26 |
| B09 | 노트북(한국어 Windows)에서 Debezium 이 `Configured SQL Server Agent status query … did not return the expected single row` WARN | Debezium 의 Agent 검사가 `servicename LIKE N'SQL Server Agent (%'` 인데 한국어 Windows 서비스 이름은 'SQL Server 에이전트'(D02 와 같은 원인) | 스트리밍·반영은 정상(4·5단계 노트북 30/30). 경고를 없애려면 `database.sqlserver.agent.status.query` 를 덮어쓴다. 아직 손대지 않음 | 확인 | `runs/20261008_1226_p4_sync1.txt`, `runs/20261008_1547_p5_sync.txt` |
| B10 | `sys.sp_cdc_help_jobs` 경고 | db_owner 만 부를 수 있음. Debezium 은 기본 폴링 간격으로 계속 | 로그 수준을 ERROR 로 올려 숨김(cdc.md §6) | 운영 | cdc.md §6 |

## C. 전환·화면

| ID | 증상 | 원인 | 조치 | 상태 | 근거 |
|---|---|---|---|---|---|
| C01 | `kdms cutover`(`--yes` 없음)가 설정 오류 1 로 끝남(CliTest) | 설정 읽기를 `--yes` 확인보다 먼저 | `--yes` 확인을 먼저 → 종료 코드 4 | 해결 | 10-08 13:10 |
| C02 | `kdms web --port` 가 무시돼 시험이 8080 충돌(`PortInUseException`) | `SpringApplicationBuilder.properties` 가 `application.yml` 보다 우선순위가 낮음 | 실행 인자 `--server.port=` 로 넘김 | 해결 | 10-08 13:10 |
| C03 | 전환 요약 표 열이 한글 단계 이름에서 어긋남, "원천 마지막 변경" 에 초가 없음 | 폭 계산, 시각 형식 | 한글을 2칸으로 세는 pad, `HH:mm:ss` | 해결 | 10-08 13:10 |
| C04 | CDC 모드 `load` 끝 안내가 4단계 방식(`schema --phase post-load`, `다음: kdms verify`)이라 혼동 | 5단계 전환 명령이 생긴 뒤 안내를 안 고침 | "kdms cutover 가 마지막 반영 뒤 적용", "다음: … kdms cutover --yes" 로(437d1dd, 단위 시험 102 통과) | 해결(재실행 전) | 10-08 15:44, `runs/20261008_1547_p5_load.txt`(고치기 전 출력) |
| C05 | 노트북 예상 다운타임 13.0초가 클라우드 8.8초보다 김 | Mac↔노트북 네트워크 왕복으로 추정 | 측정 안 함. 수치는 비율로만 보고(plan.md R9) | 확인 | 10-08 13:10·15:44 |

## D. 도구·절차

| ID | 증상 | 원인 | 조치 | 상태 | 근거 |
|---|---|---|---|---|---|
| D01 | jar 로 실행한 `status` 가 `No suitable driver found for jdbc:postgresql://…` | 실행 jar 안에서 DriverManager 가 공용 스레드 풀의 클래스로더로 드라이버를 찾음 | 드라이버 클래스를 직접 생성해 연결(`kdms.config.Jdbc`) | 해결 | 10-01 1단계 항목 |
| D02 | 노트북에서 SQL Agent 가 Running 인데 `10_enable_cdc.sql` 이 "실행 중이 아니다" 로 멈춤 | 한국어 Windows 의 `sys.dm_server_services.servicename` 이 'SQL Server 에이전트 (MSSQLSERVER)' | 실행 파일 이름(`filename LIKE N'%SQLAGENT%'`)으로 찾음(test-env.md §7). Debezium 쪽은 B09 | 해결 | PR #3 |
| D03 | 오프라인 빌드(`-o`)가 `PluginResolutionException` | 온라인 빌드에 `clean` 이 없어 clean 플러그인을 받지 않음 | 온라인 빌드를 `clean package` 로(test-env.md §6) | 해결 | PR #5 |
| D04 | 원천 접속이 실패해도 `status \| tee` 뒤 `echo $?` 가 0 | `$?` 가 tee 의 종료 코드 | 파일로 받은 뒤 종료 코드 출력(test-env.md §6) | 해결 | PR #5 |
| D05 | 3단계 확인 ③ 의 `종료 코드` 가 빈 값 | 문서 명령이 bash 의 `PIPESTATUS` 를 씀. Mac 기본 셸 zsh 에는 없음 | zsh `pipestatus[1]` 로 문서 수정 | 해결 | 10-08 11:00 |
| D06 | `load` 가 `적재하지 않음: 작업 kdms_mock 이 없다` | 노트북 PG 에 2단계 `schema` 를 아직 적용하지 않음 | `schema` 먼저 → `load` 정상 | 운영 | 10-08 11:00 |
| D07 | 5단계 S4 시험 SQL `syntax error at or near "INTO"` | FROM 안의 `INSERT … RETURNING`(시험 스크립트 문제, 코드 아님) | CTE 로 고쳐 S3 뒤 다시 실행 | 해결 | 10-08 13:10, `runs/20261008_0424_p5_cloud_s4.txt` |
| D08 | 다시 시작한 sync 출력이 `runs/_p4_sync2.txt` 로 저장 | 다른 창에서 실행해 시각 변수 `S` 가 없음 | 파일 이름만 바꿈(내용 영향 없음) | 운영 | 10-08 12:26 |
| D09 | 원천 `Login failed for user 'kodong_ms'` 원인을 모름 | 암호 불일치·혼합 인증 꺼짐·DB 접근 권한 등 여러 원인 | 노트북 오류 로그(`xp_readerrorlog`)의 `Reason:` 으로 가름(test-env.md §7) | 운영 | PR #5 |

## E. 시험 환경

| ID | 증상 | 조치 | 상태 | 근거 |
|---|---|---|---|---|
| E01 | 클라우드 Linux SQL Server 컨테이너를 서버 콜레이션 Korean_Wansung_CI_AS 로 설치하면 CDC 캡처 Job 이 `msdb.dbo.cdc_jobs` 없음 → 만든 뒤에도 `Could not load the DLL replcmds` | 클라우드는 서버 콜레이션 기본값(SQL_Latin1_General_CP1_CI_AS)으로 다시 만들고 DB 콜레이션만 Korean_Wansung_CI_AS. 노트북 Windows 설치와는 다른 문제로 봄 | 운영(클라우드 한정) | 10-08 11:57 |
| E02 | 쓰기 시험 뒤 `SourceCatalogIT`(NUL 행)·`LoadVerifyIT`(rating_id 5 변조 검출) 실패 | `30_writes.sql` 이 원천 KDMS_MOCK 의 해당 행을 바꾸거나 지움(코드 문제 아님) → `00_restore` REPLACE=1 로 원천을 되돌린 뒤 시험(test-env.md §10) | 운영 | 10-08 11:57 |
| E03 | 노트북 자체 서명 인증서로 원천 접속 `PKIX path building failed` | 시험 환경만 `trustServerCertificate=true`(DEC-21, KIS:docs/issues.md E03) | 운영 | plan.md §2, test-env.md §7 |
| E04 | KIS `00_login_mig` 가 sa 를 꺼서 클라우드에서 원천 쓰기 스크립트를 돌릴 계정이 없음 | 시험용 sysadmin 로그인을 따로 만들어 `30_writes.sql` 에 사용(클라우드 컨테이너만) | 운영(클라우드 한정) | 10-08 13:10 |
| E05 | 새 클라우드 컨테이너에서 오프라인 빌드 `./mvnw -o clean package` 가 `maven-clean-plugin … has not been downloaded` | 로컬 저장소에 clean 플러그인이 없었다 → 온라인으로 `./mvnw clean` 한 번 뒤 오프라인 빌드(rehearsal.md §2 준비 명령) | 해결 | 10-08 16:38 |
| E06 | Mac 에서 `sudo route -n delete default` 로 인터넷을 끊었는데 3분쯤 뒤 리허설의 T-N01 확인이 "인터넷이 연결돼 있다" | macOS 가 IPv4 기본 경로(`default 192.168.0.1 en0`)를 다시 만들었다(`netstat -rn`) → pf 방화벽으로 차단(DEC-49) | 해결 | 10-10 리허설 1회차 준비 |
| E07 | pf 로 인터넷을 끊은 뒤 오프라인 빌드에서 `WebSmokeTest` 5개가 `Connect` 오류(각 15초) | 규칙에 `set skip on lo0` 이 없어 127.0.0.1 내장 웹서버 연결까지 pf 가 걸렀다 → 규칙 첫 줄에 `set skip on lo0`(rehearsal.md §2) | 해결(10-10 1회차 빌드 통과) | 10-10 리허설 1회차 첫 시도 |
| E08 | `sudo pfctl -e` 로 켠 Mac 방화벽이 리허설 중 저절로 꺼짐(10-10 11:41 무렵 `Status: Disabled for … 00:02:24`, 2회차 뒤 `pf not enabled`) | 원인 미확인. macOS pf 는 참조 토큰으로 켜고 끄는데 `-e` 는 토큰 없이 켜서, 다른 서비스가 자기 토큰을 풀 때 함께 꺼지는 것으로 추정 → `-E`(토큰)로 켜고 회차 사이에 `Enabled` 확인(rehearsal.md §2). `-E` 로 켠 뒤에도 13:10 에 `pf not enabled` 라 막지 못했다. 합격한 두 회차(11:44·12:58)의 시작·끝 인터넷 차단 확인은 모두 차단 | 남음(원인 미확인) | runs/20261010_1144_p6_r1_log.txt, 20261010_1159_p6_r2_log.txt |
| E09 | 리허설 2회차 12:04:32~33 Mac→노트북 원천 1433·대상 5432 접속이 모두 `Connect timed out`(5초) → T-C10 sync 종료 코드 2, `reset` 종료 코드 1 → 대상이 초기화되지 않은 채 C 단계가 이어져 15개 불합격. 5초 뒤 sync 는 다시 접속됨 | Mac↔노트북 네트워크가 몇 초 끊김(원인 미확인: Wi-Fi·절전 등 추정). 코드 문제 아님 → `rehearsal.sh` 가 reset 실패 시 회차를 멈추게 함(`kd_reset`), 2회차 다시 | 조치함 | runs/20261010_1159_p6_r2_log.txt |
