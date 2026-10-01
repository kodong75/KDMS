# KDMS 실행 기록

KIS 와 같은 형식. 실행 결과 원문은 `runs/YYYYMMDD_HHMM_<단계>.txt`, 여기에는 경로와 요약을 적는다. 비밀번호는 어디에도 남기지 않는다.
클라우드 스레드는 노트북 DB 에 닿지 못하므로 실행 결과를 적지 않는다(지어내지 않는다).

```
## YYYY-MM-DD HH:MM · 단계 · 작업명 · Mac/노트북/클라우드
- 목적 / 환경
- 실행 명령·SQL(그대로)
- 결과(건수·시간)
- 오류 원문
- 원인 → 해결 → 재실행 결과
```

## 2026-10-01 · 1단계 · 골격 빌드 확인 · 클라우드
- 목적: 1단계 PR 의 빌드·단위 시험·라이선스 검사가 통과하는지(노트북 DB 없이).
- 환경: Linux, OpenJDK 21.0.11, Maven 3.9.11. 대상 PG 쪽은 클라우드 컨테이너의 임시 PostgreSQL 16.14 로 확인(노트북 PG 아님).
- 실행: `mvn -B package` → `mvn -o package`(오프라인) → `java -jar target/kdms.jar status` / `init` / `web`, `mvn -o test -Pintegration -Dtest=TargetSchemaIT`
- 결과: 단위 시험 31개 통과. 라이선스 허용 목록 밖 0, 라이선스 정보 없음 0. 임시 PG 에서 `test/sql/pg/00_create_kdms_db.sql` 2회 실행 성공, `init` 2회(없음 → 1, 1 → 1), `status` 대상 접속·버전 출력, TargetSchemaIT 2개 통과.
- 오류 원문: `No suitable driver found for jdbc:postgresql://…` (jar 실행 시 status)
- 원인 → 해결: 실행 jar 안에서 DriverManager 가 공용 스레드 풀의 클래스로더로 드라이버를 찾음 → 드라이버 클래스를 직접 생성해 연결(`kdms.config.Jdbc`) → 재실행 정상.
- 원천 MS-SQL 쪽 확인(SourceProbeIT, Debezium 실제 스트리밍)은 Mac 에서 해야 한다(docs/test-env.md).
